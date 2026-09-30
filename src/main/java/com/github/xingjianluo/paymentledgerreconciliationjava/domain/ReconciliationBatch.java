package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

import java.util.ArrayList;
import java.util.List;

/** 对账批次。同一批次重跑时以输入流水快照为准，结论稳定，不静默覆盖已处理差异。 */
public class ReconciliationBatch {
    private String batchId;
    private String channel;
    private BatchStatus status;
    private String channelDataHash;
    private long createdAt;
    private long finalizedAt;
    private final List<ReconciliationDetail> details = new ArrayList<>();

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public BatchStatus getStatus() { return status; }
    public void setStatus(BatchStatus status) { this.status = status; }
    public String getChannelDataHash() { return channelDataHash; }
    public void setChannelDataHash(String channelDataHash) { this.channelDataHash = channelDataHash; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(long finalizedAt) { this.finalizedAt = finalizedAt; }
    public List<ReconciliationDetail> getDetails() { return details; }
}
