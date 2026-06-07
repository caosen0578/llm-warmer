package com.pab.ficc.ibp.modelgate.server.scheduler;

import com.pab.ficc.ibp.modelgate.server.domain.entity.WarmTask;
import com.pab.ficc.ibp.modelgate.server.engine.WarmExecutionEngine;
import com.pab.ficc.ibp.modelgate.server.service.WarmTaskService;
import com.pab.framework.redis.CacheProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 动态 Cron 调度器。
 *
 * <p>职责：
 * <ol>
 *   <li>启动时加载所有已启用任务，注册 cron 触发器。</li>
 *   <li>任务新增/更新/删除时动态调整调度（cancel + re-schedule）。</li>
 *   <li>cron 触发时通过 Redis SETNX 竞争锁，抢到锁的节点执行，其余静默跳过，
 *       实现多节点任务均分。</li>
 * </ol>
 *
 * <p>多节点设计：
 * <ul>
 *   <li>每个 Pod 启动时生成唯一 UUID（{@link #NODE_ID}），不依赖 IP，重启后自然变更。</li>
 *   <li>4 个任务 2 个节点时，自然竞争结果约为各承接 2 个，实现负载均摊。</li>
 *   <li>节点崩溃后，锁 TTL 到期，下次 cron 触发时其他节点自动接管。</li>
 * </ul>
 *
 * <p>Zone 隔离：
 * 业务区 Pod 设置 {@code WARM_SCHEDULER_ENABLED=false}（默认），完全不注册 cron，
 * 不参与 Redis 锁竞争，对业务流量零影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicTaskScheduler {

    /**
     * 本 Pod 的唯一身份标识，JVM 启动时生成一次。
     * 用作 Redis 锁的 value，释放时验证归属，防止误删其他节点的锁。
     */
    static final String NODE_ID = UUID.randomUUID().toString();

    /** Redis 锁 key 前缀，方便在 Redis 管理台统一查看和清理 */
    private static final String LOCK_PREFIX = "warm:task:lock:";

    /**
     * 锁 TTL 缓冲时间（秒）。
     * 固定时长任务：TTL = durationSeconds + LOCK_BUFFER_SEC，
     * 防止任务刚结束锁就过期、下次 cron 立即重触发的边界情况。
     */
    private static final long LOCK_BUFFER_SEC = 60;

    /**
     * 长期运行任务（durationSeconds=-1）的锁 TTL（24h）。
     * 这类任务无自动结束时间，锁由 finishExecution 主动释放；
     * 24h TTL 仅作为节点崩溃时的兜底保障。
     */
    private static final long INDEFINITE_TTL_SEC = 86_400;

    private final ThreadPoolTaskScheduler taskScheduler;
    private final WarmTaskService warmTaskService;
    private final WarmExecutionEngine executionEngine;
    private final CacheProvider cacheProvider;

    /**
     * Zone 级调度开关。
     * 跑批区 Pod 通过 K8s env {@code WARM_SCHEDULER_ENABLED=true} 注入；
     * 业务区 Pod 不设置该变量，默认 false，不注册任何 cron。
     */
    @Value("${warm.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    /**
     * 已注册的 cron Future 映射，key=taskId。
     * 用于 cancel（任务更新/删除时取消旧的调度）和 isScheduled 查询。
     */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    // =========================================================================
    // 生命周期
    // =========================================================================

    /**
     * 应用就绪后加载所有启用的任务并注册 cron。
     * 使用 ApplicationReadyEvent 而非 @PostConstruct，确保 Spring 容器完全初始化后再执行。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        if (!schedulerEnabled) {
            log.info("[Scheduler] disabled on this node (warm.scheduler.enabled=false), skip cron registration");
            return;
        }
        log.info("[Scheduler] node={} starting", NODE_ID);
        List<WarmTask> enabledTasks = warmTaskService.listEnabledEntities();
        log.info("[Scheduler] loading {} enabled tasks", enabledTasks.size());
        enabledTasks.forEach(this::schedule);
    }

    // =========================================================================
    // 调度管理（供 Controller 调用）
    // =========================================================================

    /**
     * 注册或重新注册任务的 cron 调度。
     * 若任务已有旧的调度（更新场景），先 cancel 再重新注册。
     *
     * @param task 任务实体（需含有效的 cronExpr）
     */
    public void schedule(WarmTask task) {
        if (!schedulerEnabled) return;
        cancel(task.getId());   // 幂等：更新时先取消旧调度
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> tryAcquireAndExecute(task),
                    new CronTrigger(task.getCronExpr())
            );
            scheduledFutures.put(task.getId(), future);
            log.info("[Scheduler] registered task={}[{}] cron={}", task.getId(), task.getName(), task.getCronExpr());
        } catch (Exception e) {
            log.error("[Scheduler] failed to schedule task={}: {}", task.getId(), e.getMessage());
        }
    }

    /**
     * 取消任务的 cron 调度（不中断正在运行的执行）。
     * 任务删除/禁用/更新时调用。
     */
    public void cancel(Long taskId) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);   // false=不中断正在执行的触发
            log.info("[Scheduler] cancelled task={}", taskId);
        }
    }

    /**
     * 查询任务是否有有效的 cron 调度（仅检查本节点，与是否运行中无关）。
     */
    public boolean isScheduled(Long taskId) {
        ScheduledFuture<?> future = scheduledFutures.get(taskId);
        return future != null && !future.isCancelled() && !future.isDone();
    }

    // =========================================================================
    // 核心：分布式锁竞争
    // =========================================================================

    /**
     * cron 触发时的入口。多个节点同时触发，通过 Redis SET NX EX 原子命令竞争锁，
     * 成功的节点执行任务，失败的节点静默跳过。
     *
     * <p>SET NX EX = SET key value NX（不存在才设置）EX seconds（设置 TTL），原子操作，
     * 等价于 SETNX + EXPIRE 的线程安全版本。
     */
    private void tryAcquireAndExecute(WarmTask task) {
        String lockKey = LOCK_PREFIX + task.getId();
        long ttlSec = calcLockTtl(task);

        // SET key nodeId NX EX ttl — 原子 SETNX+TTL，返回 "OK" 表示加锁成功
        String result = cacheProvider.set(lockKey, NODE_ID,
                SetParams.setParams().nx().ex((int) ttlSec));

        if (!"OK".equals(result)) {
            // 其他节点持有锁，本节点本次跳过
            log.debug("[Scheduler] task={} lock held by another node, skip", task.getId());
            return;
        }

        log.info("[Scheduler] node={} acquired lock for task={}[{}] ttl={}s",
                NODE_ID, task.getId(), task.getName(), ttlSec);
        // 将锁信息传入引擎，任务结束时由引擎负责释放锁
        executionEngine.execute(task, lockKey, NODE_ID);
    }

    /**
     * 计算锁 TTL（秒）。
     * <ul>
     *   <li>固定时长任务：durationSeconds + 60s 缓冲</li>
     *   <li>长期运行任务（-1 或 null）：86400s（24h），靠 stop/优雅停机主动释放</li>
     * </ul>
     */
    private long calcLockTtl(WarmTask task) {
        Integer dur = task.getDurationSeconds();
        if (dur == null || dur <= 0) return INDEFINITE_TTL_SEC;
        return dur + LOCK_BUFFER_SEC;
    }
}
