package com.pab.ficc.ibp.modelgate.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * cron 调度线程池配置。
 *
 * <p>{@link ThreadPoolTaskScheduler} 用于 {@link com.pab.ficc.ibp.modelgate.server.scheduler.DynamicTaskScheduler}
 * 动态注册 cron 任务。每个任务的 cron 触发占用一个线程，触发后立即向 WarmExecutionEngine 的工作池提交任务并释放线程。
 *
 * <p>poolSize 建议 ≥ 最大任务数，避免 cron 触发排队延迟。
 */
@Configuration
public class SchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 线程数：建议 ≥ 预计任务总数，每个任务的 cron 触发占用一个线程（极短时间）
        scheduler.setPoolSize(20);
        scheduler.setThreadNamePrefix("warm-cron-");
        // 停机时不等待已提交的 cron 任务完成（cron 触发本身只是提交任务到引擎，耗时极短）
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
