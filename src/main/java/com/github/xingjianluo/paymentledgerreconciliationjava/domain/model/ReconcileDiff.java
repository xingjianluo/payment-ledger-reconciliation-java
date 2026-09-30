package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffType;

import java.time.Instant;

/** 对账差异，稳定键 = channel + ':' + reconDate + ':' + orderId（或渠道流水号） */
public class ReconcileDiff {
    private String diffKey;
    private String batchId;
    private String channel;
    private String reconDate;
    private String orderId;
    private String channelFlowId;
    private DiffType type;
    private Long localAmount;
    private Long channelAmount;
    private String localCurrency;
    private String channelCurrency;
    private String localStatus;
    private String channelStatus;
    private DiffStatus status;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private int seenCount;
    private boolean active = true;

    public String getDiffKey() { return diffKey; }
    public void setDiffKey(String diffKey) { this.diffKey = diffKey; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getReconDate() { return reconDate; }
    public void setReconDate(String reconDate) { this.reconDate = reconDate; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getChannelFlowId() { return channelFlowId; }
    public void setChannelFlowId(String channelFlowId) { this.channelFlowId = channelFlowId; }
    public DiffType getType() { return type; }
    public void setType(DiffType type) { this.type = type; }
    public Long getLocalAmount() { return localAmount; }
    public void setLocalAmount(Long localAmount) { this.localAmount = localAmount; }
    public Long getChannelAmount() { return channelAmount; }
    public void setChannelAmount(Long channelAmount) { this.channelAmount = channelAmount; }
    public String getLocalCurrency() { return localCurrency; }
    public void setLocalCurrency(String localCurrency) { this.localCurrency = localCurrency; }
    public String getChannelCurrency() { return channelCurrency; }
    public void setChannelCurrency(String channelCurrency) { this.channelCurrency = channelCurrency; }
    public String getLocalStatus() { return localStatus; }
    public void setLocalStatus(String localStatus) { this.localStatus = localStatus; }
    public String getChannelStatus() { return channelStatus; }
    public void setChannelStatus(String channelStatus) { this.channelStatus = channelStatus; }
    public DiffStatus getStatus() { return status; }
    public void setStatus(DiffStatus status) { this.status = status; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public int getSeenCount() { return seenCount; }
    public void setSeenCount(int seenCount) { this.seenCount = seenCount; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
