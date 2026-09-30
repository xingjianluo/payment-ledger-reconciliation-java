package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 不可变账本分录。一条支付事件产生方向相反、金额相等的一对分录，保证借贷守恒。 */
public record LedgerEntry(
        long id,
        String orderNo,
        String notificationId,
        String accountId,
        LedgerDirection direction,
        long amount,
        String currency,
        String memo,
        long postedAt
) {
}
