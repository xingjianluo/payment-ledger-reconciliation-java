package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

import java.time.Instant;

/** 对账批次：同一批数据重复跑得到稳定结果 */
public class ReconcileBatch {
    private String batchId;
    private String channel;
    private String reconDate;
    private Instant ranAt;
    private int totalFlows;
    private int totalOrders;
    private int diffCount;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getReconDate() { return reconDate; }
    public void setReconDate(String reconDate) { this.reconDate = reconDate; }
    public Instant getRanAt() { return ranAt; }
    public void setRanAt(Instant ranAt) { this.ranAt = ranAt; }
    public int getTotalFlows() { return totalFlows; }
    public void setTotalFlows(int totalFlows) { this.totalFlows = totalFlows; }
    public int getTotalOrders() { return totalOrders; }
    public void setTotalOrders(int totalOrders) { this.totalOrders = totalOrders; }
    public int getDiffCount() { return diffCount; }
    public void setDiffCount(int diffCount) { this.diffCount = diffCount; }
}
