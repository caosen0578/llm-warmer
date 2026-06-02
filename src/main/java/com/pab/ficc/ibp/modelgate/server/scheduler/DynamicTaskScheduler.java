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

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicTaskScheduler {

    /** 每个 Pod 启动时生成一次，不依赖 IP，重启后自然变更 */
    static final String NODE_ID = UUID.randomUUID().toString();

    /** 锁前缀，方便 Redis 管控台统一查看 */
    private static final String LOCK_PREFIX = "warm:task:lock:";

    /** 任务完成后锁的额外保留时间（秒），防止极短间隔的重复触发 */
    private static final long LOCK_BUFFER_SEC = 60;

    /** 长期运行任务的锁 TTL（24h），由 finishExecution 负责主动释放 */
    private static final long INDEFINITE_TTL_SEC = 86_400;

    private final ThreadPoolTaskScheduler taskScheduler;
    private final WarmTaskService warmTaskService;
    private final WarmExecutionEngine executionEngine;
    private final CacheProvider cacheProvider;

    /** 业务区 Pod 设为 false（或不设置），完全不注册 cron */
    @Value("${warm.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

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

    public void schedule(WarmTask task) {
        if (!schedulerEnabled) return;
        cancel(task.getId());
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

    public void cancel(Long taskId) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("[Scheduler] cancelled task={}", taskId);
        }
    }

    public boolean isScheduled(Long taskId) {
        ScheduledFuture<?> future = scheduledFutures.get(taskId);
        return future != null && !future.isCancelled() && !future.isDone();
    }

    // -----------------------------------------------------------------------
    // 核心：SETNX 抢锁，只有一个节点能执行，另一个节点静默跳过
    // -----------------------------------------------------------------------
    private void tryAcquireAndExecute(WarmTask task) {
        String lockKey = LOCK_PREFIX + task.getId();
        long ttlSec = calcLockTtl(task);

        // SET key value NX EX ttl — 原子性 SETNX+TTL
        String result = cacheProvider.set(lockKey, NODE_ID,
                SetParams.setParams().nx().ex((int) ttlSec));

        if (!"OK".equals(result)) {
            log.debug("[Scheduler] task={} lock held by another node, skip", task.getId());
            return;
        }

        log.info("[Scheduler] node={} acquired lock for task={}[{}] ttl={}s",
                NODE_ID, task.getId(), task.getName(), ttlSec);
        executionEngine.execute(task, lockKey, NODE_ID);
    }

    /** 锁 TTL = 任务时长 + 缓冲；长期任务设 24h，靠 finishExecution 主动释放 */
    private long calcLockTtl(WarmTask task) {
        Integer dur = task.getDurationSeconds();
        if (dur == null || dur <= 0) return INDEFINITE_TTL_SEC;
        return dur + LOCK_BUFFER_SEC;
    }
}
