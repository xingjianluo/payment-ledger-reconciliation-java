package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

import java.time.Instant;

/** 差异处理结论：只追加，不覆盖历史 */
public class ReconcileResolution {
    private String diffKey;
    private String action;
    private String comment;
    private String operator;
    private Instant resolvedAt;

    public String getDiffKey() { return diffKey; }
    public void setDiffKey(String diffKey) { this.diffKey = diffKey; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
