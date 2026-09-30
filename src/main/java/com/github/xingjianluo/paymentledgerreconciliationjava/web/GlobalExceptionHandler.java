package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一异常处理：业务拒绝返回 422 与可区分原因码，请求格式错误返回 400 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusiness(BusinessException e) {
        log.warn("business rejected reason={} message={}", e.getReason(), e.getMessage());
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "reason", e.getReason().name(),
                "message", e.getMessage() == null ? "" : e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
                "reason", "INVALID_REQUEST",
                "message", e.getMessage() == null ? "" : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "reason", "INTERNAL_ERROR",
                "message", e.getMessage() == null ? "" : e.getMessage()));
    }
}
