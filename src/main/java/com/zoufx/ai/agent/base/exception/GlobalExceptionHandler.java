package com.zoufx.ai.agent.base.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 全局异常 → HTTP 响应翻译。
 *
 * 参数校验失败统一走 HTTP 400 + JSON（不混入 SSE error 事件——后者语义是流中途出错），
 * 前端按 HTTP 错误分支处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> onValidation(WebExchangeBindException ex) {
        String message = ex.getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "VALIDATION_FAILED",
                        "message", message,
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException ex) {
        String message = Objects.requireNonNullElse(ex.getMessage(), "参数错误");
        log.warn("Bad argument: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "VALIDATION_FAILED",
                        "message", message,
                        "timestamp", Instant.now().toString()
                ));
    }
}
