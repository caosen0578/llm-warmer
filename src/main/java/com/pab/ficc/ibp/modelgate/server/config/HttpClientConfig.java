package com.pab.ficc.ibp.modelgate.server.config;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 客户端配置。
 *
 * <p>关键参数说明：
 * <ul>
 *   <li>{@code maxRequestsPerHost}：默认值仅 5，在高 TPS 预热场景下严重限制并发，必须放开。</li>
 *   <li>{@code ConnectionPool(128, 5min)}：最多保持 128 个空闲连接，避免频繁 TCP 握手。</li>
 *   <li>{@code readTimeout(120s)}：LLM 首 token 延迟可能较高，120s 足够覆盖大多数模型。</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        // 放开并发限制：默认 maxRequestsPerHost=5，高 TPS 场景必须调大
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(512);
        dispatcher.setMaxRequestsPerHost(512);

        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                // 连接池：最多 128 个空闲连接，5 分钟不活跃则关闭
                .connectionPool(new ConnectionPool(128, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)   // TCP 握手超时
                .writeTimeout(30, TimeUnit.SECONDS)     // 发送请求体超时
                .readTimeout(120, TimeUnit.SECONDS)     // 等待响应超时（含首 token 延迟）
                .build();
    }
}
