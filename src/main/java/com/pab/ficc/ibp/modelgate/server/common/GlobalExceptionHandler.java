package com.pab.ficc.ibp.modelgate.server.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>优先级（从高到低）：
 * <ol>
 *   <li>{@link BusinessException}：业务逻辑异常，返回业务错误码，不打印堆栈。</li>
 *   <li>{@link MethodArgumentNotValidException}：参数校验失败（@Valid），返回字段错误信息。</li>
 *   <li>{@link Exception}：未预期异常，打印完整堆栈，返回 500。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（主动抛出，如资源不存在、状态不允许等）。
     * 不打印堆栈，code 由异常本身携带。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验失败（@Valid @RequestBody 触发）。
     * 将多个字段错误合并为一条消息，用 "; " 分隔。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(400, msg);
    }

    /**
     * 兜底：未预期的系统异常。
     * 完整打印堆栈，方便排查；返回 500 给调用方。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("Unexpected error", e);
        return Result.fail(500, "服务内部错误: " + e.getMessage());
    }
}
