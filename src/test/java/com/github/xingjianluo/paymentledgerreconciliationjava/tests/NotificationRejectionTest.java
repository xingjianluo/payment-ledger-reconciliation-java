package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LedgerService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import com.github.xingjianluo.paymentledgerreconciliationjava.simulator.LocalPaymentSimulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 各类非法回调的拒绝原因必须可区分，且拒绝后不留账、状态不变。 */
class NotificationRejectionTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired private PaymentService paymentService;
    @org.springframework.beans.factory.annotation.Autowired private NotificationService notificationService;
    @org.springframework.beans.factory.annotation.Autowired private LedgerService ledgerService;
    @org.springframework.beans.factory.annotation.Autowired private LocalPaymentSimulator simulator;

    private void order(String no, long amount, String ccy) {
        paymentService.createOrder(no, MERCHANT, CHANNEL, amount, ccy, API_KEY);
    }

    private void assertRejected(ChannelNotification n, String apiKey, RejectReason expected) {
        var r = notificationService.handle(n, apiKey);
        assertFalse(r.accepted(), "应被拒绝: " + expected);
        assertEquals(expected.name(), r.reason(), "失败原因必须可区分");
    }

    @Test
    void unknown_order_is_rejected() {
        ChannelNotification n = simulator.notification("N-X1", "NO-SUCH",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 100L, "CNY");
        assertRejected(n, API_KEY, RejectReason.ORDER_NOT_FOUND);
    }

    @Test
    void missing_or_wrong_api_key_is_permission_denied() {
        order("O-PERM", 100L, "CNY");
        ChannelNotification n = simulator.notification("N-PERM", "O-PERM",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 100L, "CNY");
        assertRejected(n, null, RejectReason.PERMISSION_DENIED);
        assertRejected(n, "wrong-key", RejectReason.PERMISSION_DENIED);
        // 用其他商户的 key 也不行
        assertRejected(n, OTHER_API_KEY, RejectReason.PERMISSION_DENIED);
        assertEquals(PaymentStatus.CREATED, paymentService.getOrder("O-PERM").getStatus());
        assertTrue(ledgerService.entriesOfOrder("O-PERM").isEmpty());
    }

    @Test
    void merchant_mismatch_is_rejected() {
        order("O-M", 100L, "CNY");
        ChannelNotification n = simulator.notification("N-M", "O-M",
                OTHER_MERCHANT, CHANNEL, NotificationType.SUCCESS, 100L, "CNY");
        // 用对方商户 key 通过权限层，但通知商户与单据商户不一致
        assertRejected(n, OTHER_API_KEY, RejectReason.MERCHANT_MISMATCH);
    }

    @Test
    void invalid_signature_is_rejected() {
        order("O-SIG", 100L, "CNY");
        ChannelNotification n = simulator.tamperSignature(
                simulator.notification("N-SIG", "O-SIG", MERCHANT, CHANNEL,
                        NotificationType.SUCCESS, 100L, "CNY"));
        assertRejected(n, API_KEY, RejectReason.SIGNATURE_INVALID);
    }

    @Test
    void amount_mismatch_is_rejected() {
        order("O-AMT", 100L, "CNY");
        ChannelNotification n = simulator.tamperAmount(
                simulator.notification("N-AMT", "O-AMT", MERCHANT, CHANNEL,
                        NotificationType.SUCCESS, 100L, "CNY"), 200L);
        assertRejected(n, API_KEY, RejectReason.AMOUNT_MISMATCH);
        assertTrue(ledgerService.entriesOfOrder("O-AMT").isEmpty());
    }

    @Test
    void currency_mismatch_is_rejected() {
        order("O-CCY", 100L, "CNY");
        ChannelNotification n = simulator.tamperCurrency(
                simulator.notification("N-CCY", "O-CCY", MERCHANT, CHANNEL,
                        NotificationType.SUCCESS, 100L, "CNY"), "USD");
        assertRejected(n, API_KEY, RejectReason.CURRENCY_MISMATCH);
    }

    @Test
    void success_after_failure_is_illegal_transition() {
        order("O-FA", 100L, "CNY");
        notificationService.handle(simulator.notification("N-FA-1", "O-FA",
                MERCHANT, CHANNEL, NotificationType.FAILURE, 100L, "CNY"), API_KEY);
        ChannelNotification success = simulator.notification("N-FA-2", "O-FA",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 100L, "CNY");
        assertRejected(success, API_KEY, RejectReason.ILLEGAL_TRANSITION);
        assertEquals(PaymentStatus.FAILED, paymentService.getOrder("O-FA").getStatus());
    }

    @Test
    void rejected_notification_can_be_retried_after_correction_without_double_effect() {
        order("O-FIX", 100L, "CNY");
        // 先拿一个错误金额通知（被拒，占位释放，无任何业务效果）
        ChannelNotification wrong = simulator.tamperAmount(
                simulator.notification("N-FIX", "O-FIX", MERCHANT, CHANNEL,
                        NotificationType.SUCCESS, 100L, "CNY"), 999L);
        assertRejected(wrong, API_KEY, RejectReason.AMOUNT_MISMATCH);
        // 同 notificationId 用正确金额重新投递，应成功（占位此前已释放）
        ChannelNotification correct = simulator.notification("N-FIX", "O-FIX",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 100L, "CNY");
        var r = notificationService.handle(correct, API_KEY);
        assertTrue(r.accepted());
        assertFalse(r.duplicate());
        assertEquals(2, ledgerService.entriesOfOrder("O-FIX").size());
        assertEquals(PaymentStatus.SUCCESS, paymentService.getOrder("O-FIX").getStatus());
        assertTrue(ledgerService.isConserved());
    }
}
