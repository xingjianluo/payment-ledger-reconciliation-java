package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ChannelFlow;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.PaymentNotification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地模拟渠道：根据本地订单构造“渠道通知”和“渠道流水”，
 * 签名由 SignatureService 本地计算，不发起任何外部调用。
 */
@Component
public class LocalChannelSimulator {

    private final SignatureService signatureService;

    public LocalChannelSimulator(SignatureService signatureService) {
        this.signatureService = signatureService;
    }

    public PaymentNotification buildNotification(PaymentOrder order, NotificationType type,
                                                 String channelNotificationId) {
        return buildNotification(order, type, channelNotificationId,
                order.getAmount(), order.getCurrency(), order.getChannel());
    }

    /**
     * 构造带合法签名的通知，允许覆盖金额/币种/渠道，
     * 用于模拟“签名合法但内容与本地订单不一致”的渠道回调。
     */
    public PaymentNotification buildNotification(PaymentOrder order, NotificationType type,
                                                 String channelNotificationId,
                                                 long amount, String currency, String channel) {
        String id = channelNotificationId != null ? channelNotificationId
                : "NTF-" + UUID.randomUUID();
        String payload = String.join("|", channel, id, order.getOrderId(),
                String.valueOf(amount), currency, type.name());
        String sign = signatureService.sign(channel, payload);
        return new PaymentNotification(channel, id, order.getOrderId(),
                order.getMerchantId(), type.name(), amount, currency, sign);
    }

    /** 构造渠道流水；channelStatus 为渠道侧状态原文，如 SUCCESS / FAILED / REFUNDED / REVERSED */
    public ChannelFlow buildFlow(PaymentOrder order, String channelStatus, String flowId) {
        ChannelFlow flow = new ChannelFlow();
        flow.setChannelFlowId(flowId != null ? flowId : "FLW-" + UUID.randomUUID());
        flow.setOrderId(order.getOrderId());
        flow.setChannel(order.getChannel());
        flow.setMerchantId(order.getMerchantId());
        flow.setCurrency(order.getCurrency());
        flow.setAmount(order.getAmount());
        flow.setChannelStatus(channelStatus);
        flow.setOccurredAt(Instant.now());
        return flow;
    }

    /** CSV 列：flowId,orderId,channel,merchantId,currency,amount,status,occurredAt(ISO) */
    public String flowToCsv(ChannelFlow flow) {
        return String.join(",",
                flow.getChannelFlowId(),
                flow.getOrderId(),
                flow.getChannel(),
                flow.getMerchantId(),
                flow.getCurrency(),
                String.valueOf(flow.getAmount()),
                flow.getChannelStatus(),
                DateTimeFormatter.ISO_INSTANT.format(flow.getOccurredAt()));
    }
}
