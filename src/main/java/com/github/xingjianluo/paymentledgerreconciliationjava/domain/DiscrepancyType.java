package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 对账差异类型。 */
public enum DiscrepancyType {
    MATCHED,
    LOCAL_MISSING,
    CHANNEL_MISSING,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH
}
