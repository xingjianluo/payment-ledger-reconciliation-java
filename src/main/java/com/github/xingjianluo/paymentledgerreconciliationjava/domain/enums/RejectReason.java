package com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums;

/** 通知被拒绝或处理失败的可区分原因 */
public enum RejectReason {
    ORDER_NOT_FOUND,
    DUPLICATE_NOTIFICATION,
    INVALID_STATUS_TRANSITION,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    SOURCE_MISMATCH,
    SIGNATURE_INVALID,
    INVALID_REQUEST,
    LEDGER_POST_FAILED,
    DIFF_NOT_FOUND,
    DIFF_ALREADY_RESOLVED
}
