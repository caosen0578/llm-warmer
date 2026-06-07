package com.pab.ficc.ibp.modelgate.server.config;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        // maxRequestsPerHost 默认 5，高 TPS 预热场景需放开
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(512);
        dispatcher.setMaxRequestsPerHost(512);

        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(128, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)   // LLM 响应超时，120s 足够
                .build();
    }
}
