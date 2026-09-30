package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationOutcome;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;

import java.time.Instant;

/** 渠道通知处理记录：同一 channelNotificationId 唯一 */
public class NotificationRecord {
    private String channelNotificationId;
    private String orderId;
    private String channel;
    private NotificationType type;
    private NotificationOutcome outcome;
    private RejectReason rejectReason;
    private Instant processedAt;

    public String getChannelNotificationId() { return channelNotificationId; }
    public void setChannelNotificationId(String channelNotificationId) { this.channelNotificationId = channelNotificationId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public NotificationOutcome getOutcome() { return outcome; }
    public void setOutcome(NotificationOutcome outcome) { this.outcome = outcome; }
    public RejectReason getRejectReason() { return rejectReason; }
    public void setRejectReason(RejectReason rejectReason) { this.rejectReason = rejectReason; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
