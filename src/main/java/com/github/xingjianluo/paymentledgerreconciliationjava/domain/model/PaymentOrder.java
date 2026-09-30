package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;

import java.time.Instant;

/** 支付单（本地订单） */
public class PaymentOrder {
    private String orderId;
    private String merchantId;
    private String accountId;
    private String channel;
    private String currency;
    private long amount;
    private PaymentStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
