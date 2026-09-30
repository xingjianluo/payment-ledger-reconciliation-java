package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LedgerService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import com.github.xingjianluo.paymentledgerreconciliationjava.simulator.LocalPaymentSimulator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 支付状态流转、重复/并发通知的“仅生效一次”验证。 */
class NotificationFlowTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired private PaymentService paymentService;
    @org.springframework.beans.factory.annotation.Autowired private NotificationService notificationService;
    @org.springframework.beans.factory.annotation.Autowired private LedgerService ledgerService;
    @org.springframework.beans.factory.annotation.Autowired private LocalPaymentSimulator simulator;

    private PaymentOrder newOrder(String no, long amount, String ccy) {
        return paymentService.createOrder(no, MERCHANT, CHANNEL, amount, ccy, API_KEY);
    }

    @Test
    void success_notification_posts_once_and_replay_same_id_is_idempotent() {
        newOrder("O-DUP-1", 10000L, "CNY");
        ChannelNotification n1 = simulator.notification("N-DUP-1", "O-DUP-1",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 10000L, "CNY");

        var first = notificationService.handle(n1, API_KEY);
        assertTrue(first.accepted());
        assertFalse(first.duplicate());

        // 相同 notificationId 重放：幂等命中，账本不增加
        var replay = notificationService.handle(simulator.copy(n1), API_KEY);
        assertTrue(replay.accepted());
        assertTrue(replay.duplicate());
        assertEquals(RejectReason.DUPLICATE_NOTIFICATION.name(), replay.reason());

        assertEquals(PaymentStatus.SUCCESS, paymentService.getOrder("O-DUP-1").getStatus());
        List<LedgerEntry> entries = ledgerService.entriesOfOrder("O-DUP-1");
        assertEquals(2, entries.size(), "重复通知不得产生第二组分录");
        assertTrue(ledgerService.isConserved());
    }

    @Test
    void second_success_with_different_notification_id_is_rejected_as_illegal_transition() {
        newOrder("O-DUP-2", 20000L, "CNY");
        notificationService.handle(simulator.notification("N-DUP-2a", "O-DUP-2",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 20000L, "CNY"), API_KEY);

        // 渠道用不同通知号重发“成功”：状态 SUCCESS->SUCCESS 非法，必须拒绝且不能再次入账
        var again = notificationService.handle(simulator.notification("N-DUP-2b", "O-DUP-2",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 20000L, "CNY"), API_KEY);
        assertFalse(again.accepted());
        assertEquals(RejectReason.ILLEGAL_TRANSITION.name(), again.reason());

        assertEquals(2, ledgerService.entriesOfOrder("O-DUP-2").size());
        assertTrue(ledgerService.isConserved());
    }

    @Test
    void concurrent_same_notification_applies_exactly_once() throws Exception {
        newOrder("O-CONC-1", 50000L, "CNY");
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger acceptedFirst = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ChannelNotification n = simulator.notification("N-CONC-1", "O-CONC-1",
                            MERCHANT, CHANNEL, NotificationType.SUCCESS, 50000L, "CNY");
                    var r = notificationService.handle(n, API_KEY);
                    if (r.accepted() && !r.duplicate()) {
                        acceptedFirst.incrementAndGet();
                    }
                    if (r.duplicate()) {
                        duplicate.incrementAndGet();
                    }
                } catch (Exception ex) {
                    failures.add(ex.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(1, acceptedFirst.get(), "并发通知必须只有一次真正生效");
        assertEquals(threads - 1, duplicate.get(), "其余并发请求应命中幂等/在途去重");
        assertEquals(2, ledgerService.entriesOfOrder("O-CONC-1").size(), "只能有一组成对分录");
        assertEquals(PaymentStatus.SUCCESS, paymentService.getOrder("O-CONC-1").getStatus());
        assertTrue(ledgerService.isConserved());
    }

    @Test
    void failure_notification_terminates_order_without_ledger_entries() {
        newOrder("O-FAIL-1", 3000L, "CNY");
        var r = notificationService.handle(simulator.notification("N-FAIL-1", "O-FAIL-1",
                MERCHANT, CHANNEL, NotificationType.FAILURE, 3000L, "CNY"), API_KEY);
        assertTrue(r.accepted());
        assertEquals(PaymentStatus.FAILED, paymentService.getOrder("O-FAIL-1").getStatus());
        assertTrue(ledgerService.entriesOfOrder("O-FAIL-1").isEmpty());
    }

    @Test
    void refund_after_success_creates_reversing_entries() {
        newOrder("O-REF-1", 8000L, "CNY");
        notificationService.handle(simulator.notification("N-REF-1", "O-REF-1",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 8000L, "CNY"), API_KEY);
        var refund = notificationService.handle(simulator.notification("N-REF-2", "O-REF-1",
                MERCHANT, CHANNEL, NotificationType.REFUND, 8000L, "CNY"), API_KEY);
        assertTrue(refund.accepted());
        assertEquals(PaymentStatus.REFUNDED, paymentService.getOrder("O-REF-1").getStatus());
        // 入账一对 + 退款一对，净额回零
        assertEquals(4, ledgerService.entriesOfOrder("O-REF-1").size());
        long merchantBalance = ledgerService.allAccounts().stream()
                .filter(a -> a.getAccountId().equals("MERCHANT:" + MERCHANT + ":CNY"))
                .findFirst().orElseThrow().getBalance();
        assertEquals(0L, merchantBalance, "退款后商户应收净额应回到0");
        assertTrue(ledgerService.isConserved());
    }

    @Test
    void reversal_after_success_then_no_further_transition_allowed() {
        newOrder("O-REV-1", 9000L, "CNY");
        notificationService.handle(simulator.notification("N-REV-1", "O-REV-1",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 9000L, "CNY"), API_KEY);
        var rev = notificationService.handle(simulator.notification("N-REV-2", "O-REV-1",
                MERCHANT, CHANNEL, NotificationType.REVERSAL, 9000L, "CNY"), API_KEY);
        assertTrue(rev.accepted());
        assertEquals(PaymentStatus.REVERSED, paymentService.getOrder("O-REV-1").getStatus());

        // 冲正后再发退款：终态不可迁移
        var illegal = notificationService.handle(simulator.notification("N-REV-3", "O-REV-1",
                MERCHANT, CHANNEL, NotificationType.REFUND, 9000L, "CNY"), API_KEY);
        assertFalse(illegal.accepted());
        assertEquals(RejectReason.ILLEGAL_TRANSITION.name(), illegal.reason());
        assertTrue(ledgerService.isConserved());
    }
}
