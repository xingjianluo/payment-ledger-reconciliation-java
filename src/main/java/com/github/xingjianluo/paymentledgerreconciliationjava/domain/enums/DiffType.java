package com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums;

/** 对账差异类型：漏单 / 多单 / 金额不一致 / 币种不一致 / 状态不一致 */
public enum DiffType {
    MISSING_LOCAL,
    EXTRA_LOCAL,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    STATUS_MISMATCH
}
