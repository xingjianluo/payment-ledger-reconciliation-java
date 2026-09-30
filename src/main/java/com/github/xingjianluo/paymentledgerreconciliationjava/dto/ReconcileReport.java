package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

import java.util.List;

public record ReconcileReport(
        String batchId,
        String channel,
        String reconDate,
        int totalFlows,
        int totalOrders,
        int diffCount,
        List<ReconcileDiffView> diffs
) { }
