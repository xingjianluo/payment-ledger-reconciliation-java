package com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums;

/** 支付单状态：创建 -> 成功/失败；成功 -> 退款/冲正 */
public enum PaymentStatus {
    CREATED,
    SUCCESS,
    FAILED,
    REFUNDED,
    REVERSED
}
