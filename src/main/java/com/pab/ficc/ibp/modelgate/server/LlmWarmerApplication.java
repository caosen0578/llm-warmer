package com.pab.ficc.ibp.modelgate.server;

import com.ctrip.framework.apollo.spring.annotation.EnableApolloConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * llm-warmer 应用入口。
 *
 * <p>功能：GPU 利用率治理工具，通过定时批量调用 LLM 模型端点提高 GPU 利用率。
 *
 * <p>关键注解说明：
 * <ul>
 *   <li>{@code exclude DataSourceAutoConfiguration}：禁用 Spring Boot 自动数据源配置，
 *       改用内网 VenusDataSourceBean（通过 Apollo 下发连接参数）。</li>
 *   <li>{@code @EnableApolloConfig}：启用 Apollo 配置中心，application.yml 仅作本地兜底。</li>
 *   <li>{@code @EnableScheduling}：启用 @Scheduled 注解，DynamicTaskScheduler 依赖此注解。</li>
 *   <li>{@code @EnableAsync}：启用异步执行（预留，当前未使用）。</li>
 * </ul>
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@EnableApolloConfig
@EnableScheduling
@EnableAsync
public class LlmWarmerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmWarmerApplication.class, args);
    }
}
