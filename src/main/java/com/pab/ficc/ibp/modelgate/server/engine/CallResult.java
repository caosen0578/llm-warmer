package com.pab.ficc.ibp.modelgate.server.engine;

/**
 * HTTP 调用结果，封装单次 LLM 请求的所有输出信息。
 *
 * <p>使用 Java Record 实现不可变值对象，避免 setter/getter 样板代码。
 *
 * <p>两种使用场景：
 * <ul>
 *   <li><b>批量预热</b>：只关心 success / totalTokens / latencyMs，用于统计计数。</li>
 *   <li><b>测试请求</b>：还需要 responseBody / errorMessage，用于完整日志记录和前端展示。</li>
 * </ul>
 *
 * @param success       请求是否成功（HTTP 2xx 且无网络异常）
 * @param totalTokens   本次调用消耗的 token 总数（来自响应体 usage.total_tokens，失败时为 0）
 * @param responseBody  原始响应体字符串（成功时有值，失败时为 null）
 * @param latencyMs     从发出请求到收到完整响应的耗时（毫秒）
 * @param errorMessage  失败原因描述（成功时为 null）
 */
public record CallResult(
        boolean success,
        int totalTokens,
        String responseBody,
        long latencyMs,
        String errorMessage
) {

    /**
     * 构造成功结果。
     *
     * @param tokens token 消耗量
     * @param body   原始响应体
     * @param ms     耗时（毫秒）
     */
    public static CallResult ok(int tokens, String body, long ms) {
        return new CallResult(true, tokens, body, ms, null);
    }

    /**
     * 构造失败结果。
     *
     * @param error 失败原因（如 "connect timed out"、"HTTP 500: Internal Server Error"）
     * @param ms    耗时（毫秒，含网络等待时间）
     */
    public static CallResult fail(String error, long ms) {
        return new CallResult(false, 0, null, ms, error);
    }
}
