package com.pab.ficc.ibp.modelgate.server.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

/**
 * LLM HTTP 调用客户端。
 *
 * <p>设计要点：
 * <ul>
 *   <li>支持 GET / POST，完整地址由端点配置提供，不自动拼接路径。</li>
 *   <li>支持自定义请求头（JSON 对象字符串），优先级低于 apiKey 生成的 Authorization 头。</li>
 *   <li>从响应体中提取 {@code usage.total_tokens}（OpenAI 兼容格式），作为 token 统计依据。</li>
 *   <li><b>批量预热路径不打印请求/响应体日志</b>，避免高频调用污染日志。
 *       如需调试，请使用 {@code POST /api/test/request} 接口，该接口会完整记录所有内容。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmHttpClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 向模型端点发起一次 HTTP 调用，返回结构化的 {@link CallResult}。
     *
     * <p>流程：
     * <ol>
     *   <li>解析 endpoint.requestHeaders（JSON 字符串）并设置到请求头。</li>
     *   <li>若配置了 apiKey，追加 {@code Authorization: Bearer {key}} 头。</li>
     *   <li>根据 httpMethod 选择 GET 或 POST。</li>
     *   <li>执行请求，解析响应体中的 {@code usage.total_tokens}。</li>
     *   <li>任何异常均返回 {@link CallResult#fail}，不向上抛出，保证调用方稳定。</li>
     * </ol>
     *
     * @param endpoint    模型端点配置
     * @param requestBody POST 请求体（GET 时忽略）
     * @return 调用结果（成功含 token 数和响应体，失败含错误信息）
     */
    public CallResult call(ModelEndpoint endpoint, String requestBody) {
        long start = System.currentTimeMillis();
        String method = endpoint.getHttpMethod() != null
                ? endpoint.getHttpMethod().toUpperCase() : "POST";

        try {
            Request.Builder builder = new Request.Builder().url(endpoint.getBaseUrl());

            // 1. 设置自定义请求头（来自 requestHeaders 字段，JSON 对象格式）
            if (endpoint.getRequestHeaders() != null && !endpoint.getRequestHeaders().isBlank()) {
                try {
                    JsonNode headers = objectMapper.readTree(endpoint.getRequestHeaders());
                    headers.fields().forEachRemaining(e ->
                            builder.header(e.getKey(), e.getValue().asText()));
                } catch (Exception e) {
                    log.warn("Failed to parse requestHeaders for endpoint {}", endpoint.getId());
                }
            }

            // 2. apiKey → Authorization: Bearer {key}（覆盖自定义头中同名字段）
            if (endpoint.getApiKey() != null && !endpoint.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + endpoint.getApiKey());
            }

            // 3. 根据 HTTP 方法构造请求体
            if ("GET".equals(method)) {
                builder.get();
            } else {
                // POST：requestBody 为空时发送空 JSON 对象，避免服务端 400
                String body = requestBody != null ? requestBody : "{}";
                builder.post(RequestBody.create(body, JSON_TYPE));
            }

            // 4. 执行请求并解析响应
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                long ms = System.currentTimeMillis() - start;
                if (!response.isSuccessful()) {
                    return CallResult.fail("HTTP " + response.code() + ": " + response.message(), ms);
                }
                String respBody = response.body() != null ? response.body().string() : "{}";

                // 5. 从 OpenAI 兼容格式响应中提取 total_tokens
                //    路径：response.usage.total_tokens（不存在时默认 0，不影响统计）
                int tokens = 0;
                try {
                    tokens = objectMapper.readTree(respBody)
                            .path("usage").path("total_tokens").asInt(0);
                } catch (Exception ignored) {
                    // 非标准格式响应，token 计数为 0，不影响成功判断
                }
                return CallResult.ok(tokens, respBody, ms);
            }

        } catch (Exception e) {
            // 网络异常、超时等，封装为 fail 而非抛出，保证调用方（worker 线程）不崩溃
            return CallResult.fail(e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
