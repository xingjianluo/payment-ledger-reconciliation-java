package com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums;

/** 通知处理结果：首次生效 / 幂等命中 / 拒绝 */
public enum NotificationOutcome {
    APPLIED,
    IDEMPOTENT,
    REJECTED
}
