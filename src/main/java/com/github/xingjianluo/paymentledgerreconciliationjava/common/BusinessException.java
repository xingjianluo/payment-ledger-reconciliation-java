package com.github.xingjianluo.paymentledgerreconciliationjava.common;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;

/** 业务异常：携带可区分的拒绝原因码 */
public class BusinessException extends RuntimeException {
    private final RejectReason reason;

    public BusinessException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
