package com.pab.ficc.ibp.modelgate.server.engine;

/**
 * HTTP 调用结果，包含完整响应体（测试调用）和 token 统计（正式预热）。
 */
public record CallResult(
        boolean success,
        int totalTokens,
        String responseBody,
        long latencyMs,
        String errorMessage
) {
    public static CallResult ok(int tokens, String body, long ms) {
        return new CallResult(true, tokens, body, ms, null);
    }

    public static CallResult fail(String error, long ms) {
        return new CallResult(false, 0, null, ms, error);
    }
}
