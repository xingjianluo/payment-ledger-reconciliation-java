package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

/** 模拟渠道回调：sign = HmacSHA256(secret, channel|channelNotificationId|orderId|amount|currency|type) */
public record PaymentNotification(
        String channel,
        String channelNotificationId,
        String orderId,
        String merchantId,
        String type,
        Long amount,
        String currency,
        String sign
) { }
