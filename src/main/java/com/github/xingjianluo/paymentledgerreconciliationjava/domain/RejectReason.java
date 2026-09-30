package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 回调被拒绝 / 业务失败的可区分原因码。 */
public enum RejectReason {
    ORDER_NOT_FOUND,
    DUPLICATE_NOTIFICATION,
    SOURCE_MISMATCH,
    SIGNATURE_INVALID,
    MERCHANT_MISMATCH,
    PERMISSION_DENIED,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    ILLEGAL_TRANSITION,
    LEDGER_POSTING_FAILED,
    RECON_BATCH_NOT_FOUND,
    DETAIL_NOT_FOUND,
    DETAIL_ALREADY_HANDLED,
    CSV_PARSE_ERROR
}
