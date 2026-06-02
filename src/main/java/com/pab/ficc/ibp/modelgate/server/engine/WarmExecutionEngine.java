package com.pab.ficc.ibp.modelgate.server.engine;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import com.pab.ficc.ibp.modelgate.server.domain.entity.TaskExecution;
import com.pab.ficc.ibp.modelgate.server.domain.entity.WarmTask;
import com.pab.ficc.ibp.modelgate.server.domain.vo.LiveStatsVO;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelEndpointMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.TaskExecutionMapper;
import com.pab.ficc.ibp.modelgate.server.service.WarmTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class WarmExecutionEngine {

    private final LlmHttpClient llmHttpClient;
    private final WarmTaskService warmTaskService;
    private final ModelEndpointMapper endpointMapper;
    private final TaskExecutionMapper executionMapper;
    private final StringRedisTemplate redisTemplate;

    private static final int RECENT_CALL_LIMIT = 50;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Lua 脚本：仅�?key 的值等�?owner 时才删除，防止误删其他节点的�?     * 返回 1=成功释放, 0=锁不属于本节点（已过期或被其他节点持有）
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ConcurrentHashMap<Long, RunningExecution> runningMap = new ConcurrentHashMap<>();

    /** 手动触发入口：不经过分布式锁，直接运�?*/
    public void execute(WarmTask task) {
        execute(task, null, null);
    }

    /** 定时调度入口：携�?Redis 锁信息，任务结束时自动释�?*/
    public void execute(WarmTask task, String lockKey, String lockOwner) {
        if (runningMap.containsKey(task.getId())) {
            log.warn("[Engine] task={} already running on this node, skip", task.getId());
            return;
        }

        ModelEndpoint endpoint = endpointMapper.selectById(task.getEndpointId());
        if (endpoint == null || endpoint.getEnabled() != 1) {
            log.warn("[Engine] task={} endpoint={} unavailable, skip", task.getId(), task.getEndpointId());
            if (lockKey != null) releaseLock(lockKey, lockOwner);
            return;
        }

        TaskExecution execution = new TaskExecution();
        execution.setTaskId(task.getId());
        execution.setStartTime(LocalDateTime.now());
        execution.setTotalRequests(0L);
        execution.setSuccessCount(0L);
        execution.setFailCount(0L);
        execution.setTotalTokens(0L);
        execution.setStatus("RUNNING");
        execution.setCreatedAt(LocalDateTime.now());
        executionMapper.insert(execution);
        warmTaskService.updateStatus(task.getId(), "RUNNING");

        AtomicLong totalRequests = new AtomicLong();
        AtomicLong successCount  = new AtomicLong();
        AtomicLong failCount     = new AtomicLong();
        AtomicLong totalTokens   = new AtomicLong();
        AtomicBoolean stopped    = new AtomicBoolean(false);
        long startTimeMs         = System.currentTimeMillis();
        Deque<LiveStatsVO.RecentCall> recentCalls = new ArrayDeque<>(RECENT_CALL_LIMIT + 1);

        ExecutorService workers = Executors.newFixedThreadPool(task.getThreadCount(),
                r -> new Thread(r, "warm-worker-" + task.getId() + "-" + System.nanoTime()));

        boolean indefinite = task.getDurationSeconds() == null || task.getDurationSeconds() == -1;
        long endTimeMs   = indefinite ? Long.MAX_VALUE
                                      : System.currentTimeMillis() + task.getDurationSeconds() * 1000L;
        long periodNanos = TimeUnit.MILLISECONDS.toNanos(1000) / task.getTps();

        ScheduledExecutorService rateScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "warm-rate-" + task.getId()));

        ScheduledFuture<?> rateFuture = rateScheduler.scheduleAtFixedRate(() -> {
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
                LiveStatsVO.RecentCall entry = new LiveStatsVO.RecentCall(
                        LocalDateTime.now().format(TIME_FMT),
                        result.success(), result.latencyMs(), result.totalTokens(),
                        result.errorMessage());
                synchronized (recentCalls) {
                    recentCalls.addFirst(entry);
                    if (recentCalls.size() > RECENT_CALL_LIMIT) recentCalls.removeLast();
                }
            });
        }, 0, periodNanos, TimeUnit.NANOSECONDS);

        runningMap.put(task.getId(), new RunningExecution(
                rateFuture, rateScheduler, workers, execution.getId(),
                totalRequests, successCount, failCount, totalTokens, stopped,
                startTimeMs, recentCalls, lockKey, lockOwner));

        if (!indefinite) {
            rateScheduler.schedule(
                    () -> finishExecution(task.getId(), execution.getId(), "SUCCESS",
                            totalRequests, successCount, failCount, totalTokens),
                    task.getDurationSeconds() * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    public void stop(Long taskId) {
        RunningExecution running = runningMap.get(taskId);
        if (running == null) return;
        running.stopped().set(true);
        finishExecution(taskId, running.executionId(),
                "STOPPED", running.totalRequests(), running.successCount(),
                running.failCount(), running.totalTokens());
    }

    private void finishExecution(Long taskId, Long executionId, String finalStatus,
                                  AtomicLong totalRequests, AtomicLong successCount,
                                  AtomicLong failCount, AtomicLong totalTokens) {
        RunningExecution running = runningMap.remove(taskId);
        if (running != null) {
            running.stopped().set(true);
            running.rateFuture().cancel(false);
            running.rateScheduler().shutdownNow();
            running.workers().shutdownNow();
            // 释放分布式锁（手动触发时 lockKey �?null，跳过）
            if (running.lockKey() != null) {
                releaseLock(running.lockKey(), running.lockOwner());
            }
        }
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
     * Lua 原子释放：仅删除属于本节点的锁，避免误删其他节点刚续约的�?     */
    private void releaseLock(String lockKey, String lockOwner) {
        try {
            Long result = redisTemplate.execute(RELEASE_LOCK_SCRIPT,
                    Collections.singletonList(lockKey), lockOwner);
            if (Long.valueOf(1L).equals(result)) {
                log.info("[Engine] released lock key={}", lockKey);
            } else {
                log.warn("[Engine] lock key={} not owned by this node or already expired", lockKey);
            }
        } catch (Exception e) {
            log.error("[Engine] failed to release lock key={}: {}", lockKey, e.getMessage());
        }
    }

    public boolean isRunning(Long taskId) {
        return runningMap.containsKey(taskId);
    }

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
        vo.setSuccessRate(total == 0 ? 0.0 : Math.round(success * 1000.0 / total) / 10.0);
        vo.setTotalTokens(tokens);
        vo.setElapsedMs(elapsed);
        vo.setCurrentTps(elapsed == 0 ? 0.0 : Math.round(total * 10.0 / (elapsed / 1000.0)) / 10.0);

        List<LiveStatsVO.RecentCall> snapshot;
        synchronized (running.recentCalls()) {
            snapshot = new ArrayList<>(running.recentCalls());
        }
        vo.setRecentCalls(snapshot);
        return vo;
    }

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
            String lockKey,     // Redis �?key，手动触发时�?null
            String lockOwner)   // 持锁 nodeId，用�?Lua 原子释放
    {}
}
