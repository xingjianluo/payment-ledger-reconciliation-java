package com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums;

/** 账本流水类型：入账为正，退款/冲正为负 */
public enum LedgerEntryType {
    PAYMENT_IN,
    REFUND_OUT,
    REVERSAL_OUT
}
