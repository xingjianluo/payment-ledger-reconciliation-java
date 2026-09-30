package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.LedgerEntryType;

import java.time.Instant;

/** 账本流水：有符号金额（入账正、出账负），带幂等键防重 */
public class LedgerEntry {
    private String entryId;
    private String orderId;
    private String accountId;
    private String currency;
    private LedgerEntryType type;
    private long signedAmount;
    private String idempotencyKey;
    private Instant createdAt;

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LedgerEntryType getType() { return type; }
    public void setType(LedgerEntryType type) { this.type = type; }
    public long getSignedAmount() { return signedAmount; }
    public void setSignedAmount(long signedAmount) { this.signedAmount = signedAmount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
