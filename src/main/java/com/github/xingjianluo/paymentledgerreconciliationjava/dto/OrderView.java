package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;

public record OrderView(
        String orderId,
        String merchantId,
        String accountId,
        String channel,
        String currency,
        long amount,
        PaymentStatus status,
        int version
) { }
