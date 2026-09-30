package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffType;

public record ReconcileDiffView(
        String diffKey,
        String orderId,
        String channelFlowId,
        DiffType type,
        DiffStatus status,
        Long localAmount,
        Long channelAmount,
        String localCurrency,
        String channelCurrency,
        String localStatus,
        String channelStatus,
        int seenCount
) { }
