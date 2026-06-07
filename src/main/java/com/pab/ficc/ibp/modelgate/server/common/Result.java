package com.pab.ficc.ibp.modelgate.server.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

/**
 * 统一 HTTP 响应包装。
 *
 * <p>格式：
 * <pre>
 * {
 *   "code":      0,              // 0=成功，非 0=失败
 *   "message":   "success",
 *   "data":      {},             // 业务数据，为 null 时不序列化
 *   "timestamp": 1717000000000   // 服务端时间戳（毫秒）
 * }
 * </pre>
 *
 * <p>约定：
 * <ul>
 *   <li>code=0：请求成功</li>
 *   <li>code=400：业务逻辑异常（参数错误、状态不允许等）</li>
 *   <li>code=404：资源不存在</li>
 *   <li>code=500：系统内部异常</li>
 * </ul>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;
    /** 服务端时间戳（毫秒），用于排查时钟偏差问题 */
    private final long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toEpochMilli();
    }

    /** 成功响应（携带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    /** 成功响应（无数据，适用于写操作） */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 失败响应（指定错误码） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败响应（默认 500） */
    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }
}
