package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 渠道流水（对账单中的一行）。 */
public class ChannelTransaction {
    private String orderNo;
    private String merchantId;
    private String channel;
    private long amount;
    private String currency;
    private String status;
    private long occurredAt;

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getOccurredAt() { return occurredAt; }
    public void setOccurredAt(long occurredAt) { this.occurredAt = occurredAt; }
}
