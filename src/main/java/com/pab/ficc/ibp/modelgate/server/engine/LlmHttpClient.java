package com.pab.ficc.ibp.modelgate.server.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmHttpClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 发起 HTTP 请求，返回 {@link CallResult}，包含 token 数量和原始响应体。
     * <p>正式预热调用量极大，此方法不打印请求/响应体日志，避免日志污染。
     * 如需记录响应内容，请使用 {@code /api/test/request} 接口。
     */
    public CallResult call(ModelEndpoint endpoint, String requestBody) {
        long start = System.currentTimeMillis();
        String method = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().toUpperCase() : "POST";

        try {
            Request.Builder builder = new Request.Builder().url(endpoint.getBaseUrl());

            if (endpoint.getRequestHeaders() != null && !endpoint.getRequestHeaders().isBlank()) {
                try {
                    JsonNode headers = objectMapper.readTree(endpoint.getRequestHeaders());
                    headers.fields().forEachRemaining(e ->
                            builder.header(e.getKey(), e.getValue().asText()));
                } catch (Exception e) {
                    log.warn("Failed to parse requestHeaders for endpoint {}", endpoint.getId());
                }
            }

            if (endpoint.getApiKey() != null && !endpoint.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + endpoint.getApiKey());
            }

            if ("GET".equals(method)) {
                builder.get();
            } else {
                String body = requestBody != null ? requestBody : "{}";
                builder.post(RequestBody.create(body, JSON_TYPE));
            }

            try (Response response = httpClient.newCall(builder.build()).execute()) {
                long ms = System.currentTimeMillis() - start;
                if (!response.isSuccessful()) {
                    return CallResult.fail("HTTP " + response.code() + ": " + response.message(), ms);
                }
                String respBody = response.body() != null ? response.body().string() : "{}";
                int tokens = 0;
                try {
                    tokens = objectMapper.readTree(respBody).path("usage").path("total_tokens").asInt(0);
                } catch (Exception ignored) {}
                return CallResult.ok(tokens, respBody, ms);
            }
        } catch (Exception e) {
            return CallResult.fail(e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
