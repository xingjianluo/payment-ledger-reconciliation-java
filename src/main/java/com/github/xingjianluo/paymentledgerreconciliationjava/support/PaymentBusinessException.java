package com.github.xingjianluo.paymentledgerreconciliationjava.support;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.RejectReason;

/** 可区分原因码的业务异常（回调拒绝、非法操作等）。 */
public class PaymentBusinessException extends RuntimeException {

    private final RejectReason reason;

    public PaymentBusinessException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
