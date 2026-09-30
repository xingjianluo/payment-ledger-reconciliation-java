package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

import java.util.List;

/** 渠道流水行（CSV 解析后或直接 JSON 提交） */
public record ReconcileRequest(
        String channel,
        String reconDate,
        List<String> csvLines
) { }
