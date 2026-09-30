package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 支付单。金额为最小货币单位长整型（如分），避免浮点漂移。 */
public class PaymentOrder {
    private String orderNo;
    private String merchantId;
    private String channel;
    private long amount;
    private String currency;
    private PaymentStatus status;
    private String lastNotificationId;
    private long createdAt;
    private long updatedAt;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getLastNotificationId() { return lastNotificationId; }
    public void setLastNotificationId(String lastNotificationId) { this.lastNotificationId = lastNotificationId; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
