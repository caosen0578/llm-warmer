package com.pab.ficc.ibp.modelgate.server.engine;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import com.pab.ficc.ibp.modelgate.server.domain.entity.TaskExecution;
import com.pab.ficc.ibp.modelgate.server.domain.entity.WarmTask;
import com.pab.ficc.ibp.modelgate.server.domain.vo.LiveStatsVO;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelEndpointMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.TaskExecutionMapper;
import com.pab.ficc.ibp.modelgate.server.service.WarmTaskService;
import com.pab.framework.redis.CacheProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预热任务执行引擎。
 *
 * <p>核心设计：两层独立控制
 * <ul>
 *   <li><b>速率层</b>：单线程 ScheduledExecutor，按 1_000_000_000/tps 纳秒间隔向工作池提交任务，
 *       保证每秒精确发出 tps 个请求。</li>
 *   <li><b>并发层</b>：FixedThreadPool(threadCount) 控制同时在途（飞行中）的 HTTP 请求数，
 *       超出上限的任务在队列中等待，防止连接数爆炸。</li>
 * </ul>
 *
 * <p>最优配置参考 Little's Law：threadCount ≈ tps × 平均响应时间(s)
 *
 * <p>线程安全：每个任务的统计计数器使用 AtomicLong，recentCalls 队列使用 synchronized 块，
 * runningMap 使用 ConcurrentHashMap。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmExecutionEngine {

    private final LlmHttpClient llmHttpClient;
    private final WarmTaskService warmTaskService;
    private final ModelEndpointMapper endpointMapper;
    private final TaskExecutionMapper executionMapper;
    private final CacheProvider cacheProvider;

    /** 内存中保留的最近调用明细条数，用于实时监控面板展示 */
    private static final int RECENT_CALL_LIMIT = 50;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Lua 原子释放锁脚本。
     * 仅当 key 的值等于持锁方 UUID 时才执行删除，防止以下场景中的误删：
     * 本节点锁 TTL 已过期 → 其他节点重新加锁 → 本节点事后执行 del 误删新锁。
     * 返回 1=成功释放，0=锁不属于本节点。
     */
    private static final String RELEASE_LOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] " +
            "then return redis.call('del', KEYS[1]) " +
            "else return 0 end";

    /**
     * 当前节点正在运行的任务映射。
     * key=taskId，value=运行上下文（含线程池、计数器、锁信息等）。
     * 用于幂等检查、stop 操作、live-stats 查询和优雅停机。
     */
    private final ConcurrentHashMap<Long, RunningExecution> runningMap = new ConcurrentHashMap<>();

    // =========================================================================
    // 公开接口
    // =========================================================================

    /**
     * 手动触发入口（来自 HTTP 接口）。
     * 不经过分布式锁，直接在本节点运行，用于调试和临时触发。
     */
    public void execute(WarmTask task) {
        execute(task, null, null);
    }

    /**
     * 定时调度入口（来自 DynamicTaskScheduler）。
     *
     * @param task      要执行的预热任务
     * @param lockKey   Redis 分布式锁 key（如 warm:task:lock:1）
     * @param lockOwner 当前节点的 UUID，用于 Lua 原子释放
     */
    public void execute(WarmTask task, String lockKey, String lockOwner) {
        // 幂等检查：同一节点不重复运行同一任务
        if (runningMap.containsKey(task.getId())) {
            log.warn("[Engine] task={} already running on this node, skip", task.getId());
            // 本次竞争到了锁但任务已在跑，立即释放锁，让其他节点有机会接管
            if (lockKey != null) releaseLock(lockKey, lockOwner);
            return;
        }

        // 校验端点可用性（被禁用的端点不执行）
        ModelEndpoint endpoint = endpointMapper.selectById(task.getEndpointId());
        if (endpoint == null || endpoint.getEnabled() != 1) {
            log.warn("[Engine] task={} endpoint={} unavailable, skip", task.getId(), task.getEndpointId());
            if (lockKey != null) releaseLock(lockKey, lockOwner);
            return;
        }

        // 写入执行历史记录，status=RUNNING
        TaskExecution execution = buildExecution(task.getId());
        executionMapper.insert(execution);
        warmTaskService.updateStatus(task.getId(), "RUNNING");

        // -----------------------------------------------------------------------
        // 统计计数器：全部使用 AtomicLong，无锁高性能，多个 worker 线程并发累加
        // -----------------------------------------------------------------------
        AtomicLong totalRequests = new AtomicLong();
        AtomicLong successCount  = new AtomicLong();
        AtomicLong failCount     = new AtomicLong();
        AtomicLong totalTokens   = new AtomicLong();
        AtomicBoolean stopped    = new AtomicBoolean(false);
        long startTimeMs         = System.currentTimeMillis();

        // 最近 N 条调用明细，新记录 addFirst，超限时 removeLast，始终最新在前
        Deque<LiveStatsVO.RecentCall> recentCalls = new ArrayDeque<>(RECENT_CALL_LIMIT + 1);

        // -----------------------------------------------------------------------
        // 并发层：FixedThreadPool 限制同时飞行的 HTTP 请求数
        // threadCount 过小 → 队列积压，实际 TPS 打不满
        // threadCount 过大 → 线程栈内存浪费（每线程默认 ~1MB）
        // -----------------------------------------------------------------------
        ExecutorService workers = Executors.newFixedThreadPool(
                task.getThreadCount(),
                r -> new Thread(r, "warm-worker-" + task.getId() + "-" + System.nanoTime()));

        // -----------------------------------------------------------------------
        // 速率层：单线程 ScheduledExecutor 以纳秒精度定时向 workers 提交任务
        // periodNanos = 1s / tps，例如 tps=10 → 每 100ms 提交一次
        // -----------------------------------------------------------------------
        boolean indefinite = task.getDurationSeconds() == null || task.getDurationSeconds() == -1;
        long endTimeMs   = indefinite ? Long.MAX_VALUE
                                      : System.currentTimeMillis() + task.getDurationSeconds() * 1000L;
        long periodNanos = TimeUnit.MILLISECONDS.toNanos(1000) / task.getTps();

        ScheduledExecutorService rateScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "warm-rate-" + task.getId()));

        ScheduledFuture<?> rateFuture = rateScheduler.scheduleAtFixedRate(() -> {
            // 时间到或已被 stop，不再提交新任务（已提交的继续跑完）
            if (stopped.get() || System.currentTimeMillis() >= endTimeMs) return;

            workers.submit(() -> {
                totalRequests.incrementAndGet();
                CallResult result = llmHttpClient.call(endpoint, task.getRequestBody());

                if (result.success()) {
                    successCount.incrementAndGet();
                    totalTokens.addAndGet(result.totalTokens());
                } else {
                    failCount.incrementAndGet();
                }

                // 写入调用明细环形缓冲（最多保留 RECENT_CALL_LIMIT 条）
                LiveStatsVO.RecentCall entry = new LiveStatsVO.RecentCall(
                        LocalDateTime.now().format(TIME_FMT),
                        result.success(), result.latencyMs(),
                        result.totalTokens(), result.errorMessage());
                synchronized (recentCalls) {
                    recentCalls.addFirst(entry);
                    if (recentCalls.size() > RECENT_CALL_LIMIT) recentCalls.removeLast();
                }
            });
        }, 0, periodNanos, TimeUnit.NANOSECONDS);

        // 保存运行上下文，供 stop/live-stats/优雅停机使用
        runningMap.put(task.getId(), new RunningExecution(
                rateFuture, rateScheduler, workers, execution.getId(),
                totalRequests, successCount, failCount, totalTokens, stopped,
                startTimeMs, recentCalls, lockKey, lockOwner));

        // 固定时长任务：到期后由 rateScheduler 自动触发收尾
        if (!indefinite) {
            rateScheduler.schedule(
                    () -> finishExecution(task.getId(), execution.getId(), "SUCCESS",
                            totalRequests, successCount, failCount, totalTokens),
                    task.getDurationSeconds() * 1000L, TimeUnit.MILLISECONDS);
        }
        // durationSeconds=-1 的长期任务：只能通过 stop() 或优雅停机结束
    }

    /**
     * 手动停止运行中的任务。
     * 设置 stopped 标志位（速率调度器检测到后停止提交），然后调用 finishExecution 收尾。
     */
    public void stop(Long taskId) {
        RunningExecution running = runningMap.get(taskId);
        if (running == null) return;
        // 先标记停止，防止 rateScheduler 在 finishExecution 执行期间继续提交任务
        running.stopped().set(true);
        finishExecution(taskId, running.executionId(),
                "STOPPED", running.totalRequests(), running.successCount(),
                running.failCount(), running.totalTokens());
    }

    /**
     * 查询任务是否在本节点运行中（仅本节点视角，不跨节点）。
     */
    public boolean isRunning(Long taskId) {
        return runningMap.containsKey(taskId);
    }

    /**
     * 获取运行中任务的实时统计数据，供前端轮询展示。
     * 直接读取内存中的 AtomicLong，零 DB 开销。
     */
    public LiveStatsVO getLiveStats(Long taskId) {
        LiveStatsVO vo = new LiveStatsVO();
        RunningExecution running = runningMap.get(taskId);
        if (running == null) {
            vo.setRunning(false);
            return vo;
        }

        long total   = running.totalRequests().get();
        long success = running.successCount().get();
        long fail    = running.failCount().get();
        long tokens  = running.totalTokens().get();
        long elapsed = System.currentTimeMillis() - running.startTimeMs();

        vo.setRunning(true);
        vo.setTotalRequests(total);
        vo.setSuccessCount(success);
        vo.setFailCount(fail);
        // 成功率：保留一位小数，如 98.3%
        vo.setSuccessRate(total == 0 ? 0.0 : Math.round(success * 1000.0 / total) / 10.0);
        vo.setTotalTokens(tokens);
        vo.setElapsedMs(elapsed);
        // 当前 TPS：总请求数 / 已运行秒数，保留一位小数
        vo.setCurrentTps(elapsed == 0 ? 0.0 : Math.round(total * 10.0 / (elapsed / 1000.0)) / 10.0);

        // 拷贝快照，避免调用方持有对 recentCalls 的引用
        List<LiveStatsVO.RecentCall> snapshot;
        synchronized (running.recentCalls()) {
            snapshot = new ArrayList<>(running.recentCalls());
        }
        vo.setRecentCalls(snapshot);
        return vo;
    }

    // =========================================================================
    // 内部方法
    // =========================================================================

    /**
     * 任务收尾：关闭线程池、更新 DB 执行记录、释放 Redis 锁、恢复任务状态为 IDLE。
     * 由三个入口调用：到期自动结束、手动 stop、优雅停机。
     */
    private void finishExecution(Long taskId, Long executionId, String finalStatus,
                                  AtomicLong totalRequests, AtomicLong successCount,
                                  AtomicLong failCount, AtomicLong totalTokens) {
        RunningExecution running = runningMap.remove(taskId);
        if (running != null) {
            running.stopped().set(true);
            // cancel(false)：不中断正在执行的调度任务，但不再触发新的
            running.rateFuture().cancel(false);
            // shutdownNow：向线程发送中断信号，等待线程响应（HTTP 阻塞线程可能不会立即停止）
            running.rateScheduler().shutdownNow();
            running.workers().shutdownNow();

            // 释放分布式锁（手动触发时 lockKey 为 null，跳过）
            if (running.lockKey() != null) {
                releaseLock(running.lockKey(), running.lockOwner());
            }
        }

        // 将最终统计写回 DB
        executionMapper.update(null, new LambdaUpdateWrapper<TaskExecution>()
                .eq(TaskExecution::getId, executionId)
                .set(TaskExecution::getEndTime, LocalDateTime.now())
                .set(TaskExecution::getTotalRequests, totalRequests.get())
                .set(TaskExecution::getSuccessCount, successCount.get())
                .set(TaskExecution::getFailCount, failCount.get())
                .set(TaskExecution::getTotalTokens, totalTokens.get())
                .set(TaskExecution::getStatus, finalStatus));

        warmTaskService.updateStatus(taskId, "IDLE");
        log.info("[Engine] task={} finished status={} requests={} tokens={}",
                taskId, finalStatus, totalRequests.get(), totalTokens.get());
    }

    /**
     * 通过 Lua 脚本原子释放 Redis 锁。
     * 原子性保证：get + del 在同一 Lua 调用中执行，不存在 TOCTOU 竞争。
     */
    private void releaseLock(String lockKey, String lockOwner) {
        try {
            Object result = cacheProvider.eval(
                    RELEASE_LOCK_LUA,
                    Collections.singletonList(lockKey),
                    Collections.singletonList(lockOwner));
            if (Long.valueOf(1L).equals(result)) {
                log.info("[Engine] released lock key={}", lockKey);
            } else {
                log.warn("[Engine] lock key={} not owned by this node or already expired", lockKey);
            }
        } catch (Exception e) {
            log.error("[Engine] failed to release lock key={}: {}", lockKey, e.getMessage());
        }
    }

    /**
     * 优雅停机：Spring 容器关闭时调用，确保所有运行中任务正常收尾。
     * 防止 Pod 重启后 task_execution 状态长期停留在 RUNNING。
     */
    @PreDestroy
    public void onShutdown() {
        if (runningMap.isEmpty()) return;
        log.info("[Engine] shutdown — stopping {} running task(s)", runningMap.size());
        new ArrayList<>(runningMap.keySet()).forEach(taskId -> {
            if (runningMap.containsKey(taskId)) stop(taskId);
        });
    }

    /** 构建初始执行记录（status=RUNNING） */
    private TaskExecution buildExecution(Long taskId) {
        TaskExecution execution = new TaskExecution();
        execution.setTaskId(taskId);
        execution.setStartTime(LocalDateTime.now());
        execution.setTotalRequests(0L);
        execution.setSuccessCount(0L);
        execution.setFailCount(0L);
        execution.setTotalTokens(0L);
        execution.setStatus("RUNNING");
        execution.setCreatedAt(LocalDateTime.now());
        return execution;
    }

    // =========================================================================
    // 运行上下文（不可变记录，存储单次任务执行的所有运行时状态）
    // =========================================================================

    /**
     * 单次任务执行的运行时上下文，存储于 runningMap。
     *
     * @param rateFuture    速率调度器的 Future，用于取消定时提交
     * @param rateScheduler 速率调度器本体，用于关闭
     * @param workers       工作线程池，真正执行 HTTP 调用
     * @param executionId   task_execution 表的主键，收尾时更新该行
     * @param totalRequests 累计提交请求数
     * @param successCount  成功次数
     * @param failCount     失败次数
     * @param totalTokens   累计 token 数
     * @param stopped       停止信号，速率调度器检测到后不再提交新任务
     * @param startTimeMs   启动时间戳，用于计算已运行时长和当前 TPS
     * @param recentCalls   最近 N 条调用明细（最新在前），synchronized 保护
     * @param lockKey       Redis 锁 key，null 表示手动触发（无锁）
     * @param lockOwner     持锁节点 UUID，Lua 脚本释放时用于验证归属
     */
    private record RunningExecution(
            ScheduledFuture<?> rateFuture,
            ScheduledExecutorService rateScheduler,
            ExecutorService workers,
            Long executionId,
            AtomicLong totalRequests,
            AtomicLong successCount,
            AtomicLong failCount,
            AtomicLong totalTokens,
            AtomicBoolean stopped,
            long startTimeMs,
            Deque<LiveStatsVO.RecentCall> recentCalls,
            String lockKey,
            String lockOwner)
    {}
}
