package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 支付单生命周期状态。 */
public enum PaymentStatus {
    CREATED,
    SUCCESS,
    FAILED,
    REFUNDED,
    REVERSED
}
