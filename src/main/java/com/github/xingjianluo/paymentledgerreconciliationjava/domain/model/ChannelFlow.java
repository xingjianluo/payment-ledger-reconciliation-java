package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

import java.time.Instant;

/** 渠道流水（从模拟渠道导入的对账数据） */
public class ChannelFlow {
    private String channelFlowId;
    private String orderId;
    private String channel;
    private String merchantId;
    private String currency;
    private long amount;
    private String channelStatus;
    private Instant occurredAt;

    public String getChannelFlowId() { return channelFlowId; }
    public void setChannelFlowId(String channelFlowId) { this.channelFlowId = channelFlowId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getChannelStatus() { return channelStatus; }
    public void setChannelStatus(String channelStatus) { this.channelStatus = channelStatus; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
