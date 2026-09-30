package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

public record CreateOrderRequest(
        String merchantId,
        String accountId,
        String channel,
        String currency,
        Long amount
) { }
