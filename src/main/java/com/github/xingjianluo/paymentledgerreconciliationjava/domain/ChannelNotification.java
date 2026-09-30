package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 渠道回调通知（本地模拟）。 */
public class ChannelNotification {
    private String notificationId;
    private String orderNo;
    private String merchantId;
    private String channel;
    private NotificationType type;
    private long amount;
    private String currency;
    private String status;
    private String signature;

    public String getNotificationId() { return notificationId; }
    public void setNotificationId(String notificationId) { this.notificationId = notificationId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}
