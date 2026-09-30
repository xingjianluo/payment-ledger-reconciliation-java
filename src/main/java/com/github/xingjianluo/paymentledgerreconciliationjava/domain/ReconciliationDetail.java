package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 对账明细：一个渠道流水 / 本地单据的比对结论。 */
public class ReconciliationDetail {
    private long id;
    private String batchId;
    private String orderNo;
    private DiscrepancyType discrepancyType;
    private DiscrepancyStatus discrepancyStatus;
    private Long localAmount;
    private String localCurrency;
    private PaymentStatus localStatus;
    private Long channelAmount;
    private String channelCurrency;
    private String channelStatus;
    private String handleNote;
    private String handledBy;
    private long createdAt;
    private long handledAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public DiscrepancyType getDiscrepancyType() { return discrepancyType; }
    public void setDiscrepancyType(DiscrepancyType discrepancyType) { this.discrepancyType = discrepancyType; }
    public DiscrepancyStatus getDiscrepancyStatus() { return discrepancyStatus; }
    public void setDiscrepancyStatus(DiscrepancyStatus discrepancyStatus) { this.discrepancyStatus = discrepancyStatus; }
    public Long getLocalAmount() { return localAmount; }
    public void setLocalAmount(Long localAmount) { this.localAmount = localAmount; }
    public String getLocalCurrency() { return localCurrency; }
    public void setLocalCurrency(String localCurrency) { this.localCurrency = localCurrency; }
    public PaymentStatus getLocalStatus() { return localStatus; }
    public void setLocalStatus(PaymentStatus localStatus) { this.localStatus = localStatus; }
    public Long getChannelAmount() { return channelAmount; }
    public void setChannelAmount(Long channelAmount) { this.channelAmount = channelAmount; }
    public String getChannelCurrency() { return channelCurrency; }
    public void setChannelCurrency(String channelCurrency) { this.channelCurrency = channelCurrency; }
    public String getChannelStatus() { return channelStatus; }
    public void setChannelStatus(String channelStatus) { this.channelStatus = channelStatus; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String handledBy) { this.handledBy = handledBy; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getHandledAt() { return handledAt; }
    public void setHandledAt(long handledAt) { this.handledAt = handledAt; }
}
