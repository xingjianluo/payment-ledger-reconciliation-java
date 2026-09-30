package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.support.PaymentBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一异常出口：业务异常携带可区分原因码。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentBusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(PaymentBusinessException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "accepted", false,
                "reason", ex.getReason().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
