package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationOutcome;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;

public record NotificationResult(
        String orderId,
        String orderStatus,
        NotificationOutcome outcome,
        RejectReason rejectReason,
        String message
) { }
