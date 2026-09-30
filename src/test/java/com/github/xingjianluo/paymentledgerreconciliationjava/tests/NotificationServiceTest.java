package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationOutcome;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.CreateOrderRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.NotificationResult;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.PaymentNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LocalChannelSimulator;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

/** 通知处理：状态流转、重复通知、各拒绝原因必须可区分 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NotificationServiceTest {

    @Autowired PaymentService paymentService;
    @Autowired NotificationService notificationService;
    @Autowired LocalChannelSimulator simulator;

    private PaymentOrder newOrder(long amount) {
        return paymentService.createOrder(new CreateOrderRequest(
                "MCH-001", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", amount));
    }

    @Test
    void success_then_refund_flow() {
        PaymentOrder order = newOrder(1000);
        NotificationResult ok = notificationService.handle(
                simulator.buildNotification(order, NotificationType.PAYMENT_SUCCESS, "N1"));
        assertEquals(NotificationOutcome.APPLIED, ok.outcome());
        assertEquals(PaymentStatus.SUCCESS.name(), ok.orderStatus());

        NotificationResult refund = notificationService.handle(
                simulator.buildNotification(order, NotificationType.REFUND, "N2"));
        assertEquals(NotificationOutcome.APPLIED, refund.outcome());
        assertEquals(PaymentStatus.REFUNDED.name(), refund.orderStatus());
    }

    @Test
    void duplicate_notification_applies_once_and_is_idempotent() {
        PaymentOrder order = newOrder(2000);
        PaymentNotification n = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "DUP-1");

        NotificationResult first = notificationService.handle(n);
        NotificationResult second = notificationService.handle(n);
        NotificationResult third = notificationService.handle(n);

        assertEquals(NotificationOutcome.APPLIED, first.outcome());
        assertEquals(NotificationOutcome.IDEMPOTENT, second.outcome());
        assertEquals(NotificationOutcome.IDEMPOTENT, third.outcome());
        assertEquals(PaymentStatus.SUCCESS, paymentService.getOrder(order.getOrderId()).getStatus());
    }

    @Test
    void reject_invalid_status_transition() {
        PaymentOrder order = newOrder(100);
        // CREATED 状态不能直接退款
        NotificationResult r = notificationService.handle(
                simulator.buildNotification(order, NotificationType.REFUND, "NS-1"));
        assertEquals(NotificationOutcome.REJECTED, r.outcome());
        assertEquals(RejectReason.INVALID_STATUS_TRANSITION, r.rejectReason());
        assertEquals(PaymentStatus.CREATED.name(), r.orderStatus());
    }

    @Test
    void reject_amount_mismatch() {
        PaymentOrder order = newOrder(100);
        // 渠道侧对 999 金额重新签名：签名合法但与本地金额不一致
        PaymentNotification tampered = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "AM-1", 999L, "CNY", "MOCK_ALIPAY");
        NotificationResult r = notificationService.handle(tampered);
        assertEquals(RejectReason.AMOUNT_MISMATCH, r.rejectReason());
    }

    @Test
    void reject_currency_mismatch() {
        PaymentOrder order = newOrder(100);
        PaymentNotification tampered = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "CM-1", 100L, "USD", "MOCK_ALIPAY");
        NotificationResult r = notificationService.handle(tampered);
        assertEquals(RejectReason.CURRENCY_MISMATCH, r.rejectReason());
    }

    @Test
    void reject_bad_signature() {
        PaymentOrder order = newOrder(100);
        PaymentNotification n = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "SG-1");
        PaymentNotification forged = new PaymentNotification(n.channel(), n.channelNotificationId(),
                n.orderId(), n.merchantId(), n.type(), n.amount(), n.currency(), "deadbeef");
        NotificationResult r = notificationService.handle(forged);
        assertEquals(RejectReason.SIGNATURE_INVALID, r.rejectReason());
    }

    @Test
    void reject_source_mismatch_when_channel_differs() {
        PaymentOrder order = newOrder(100);
        // 用微信渠道密钥签名后发到本支付宝订单：来源不匹配
        PaymentNotification wrongChannel = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "SC-1", 100L, "CNY", "MOCK_WECHAT");
        NotificationResult r = notificationService.handle(wrongChannel);
        assertEquals(RejectReason.SOURCE_MISMATCH, r.rejectReason());
    }

    @Test
    void reject_merchant_mismatch() {
        PaymentOrder order = newOrder(100);
        PaymentNotification n = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "MC-1");
        PaymentNotification wrongMerchant = new PaymentNotification(n.channel(),
                n.channelNotificationId(), n.orderId(), "MCH-EVIL", n.type(),
                n.amount(), n.currency(), n.sign());
        NotificationResult r = notificationService.handle(wrongMerchant);
        assertEquals(RejectReason.SOURCE_MISMATCH, r.rejectReason());
    }

    @Test
    void reject_order_not_found_then_retry_after_creation_succeeds() {
        PaymentOrder order = newOrder(100);
        PaymentNotification n = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "ONF-1");
        PaymentNotification forOtherOrder = new PaymentNotification(n.channel(),
                n.channelNotificationId(), "ORD-NONEXIST", n.merchantId(), n.type(),
                n.amount(), n.currency(), n.sign());

        NotificationResult missing = notificationService.handle(forOtherOrder);
        assertEquals(RejectReason.ORDER_NOT_FOUND, missing.rejectReason());
        // 通知ID未被占用：发给真实订单仍可生效
        NotificationResult ok = notificationService.handle(new PaymentNotification(n.channel(),
                n.channelNotificationId(), order.getOrderId(), n.merchantId(), n.type(),
                n.amount(), n.currency(), n.sign()));
        assertEquals(NotificationOutcome.APPLIED, ok.outcome());
    }

    @Test
    void reversal_on_failed_order_is_rejected_by_state_machine() {
        PaymentOrder order = newOrder(300);
        notificationService.handle(simulator.buildNotification(
                order, NotificationType.PAYMENT_FAILED, "RF-1"));
        assertEquals(PaymentStatus.FAILED, paymentService.getOrder(order.getOrderId()).getStatus());

        // FAILED 单无入账，冲正只允许发生在 SUCCESS 上：状态机拒绝且无账本动作
        var r = notificationService.handle(simulator.buildNotification(
                order, NotificationType.REVERSAL, "RF-2"));
        assertEquals(NotificationOutcome.REJECTED, r.outcome());
        assertEquals(RejectReason.INVALID_STATUS_TRANSITION, r.rejectReason());
        assertEquals(PaymentStatus.FAILED, paymentService.getOrder(order.getOrderId()).getStatus());
    }
}
