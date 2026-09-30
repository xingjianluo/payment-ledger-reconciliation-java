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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 并发通知：只能生效一次，余额/状态不漂移 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NotificationConcurrencyTest {

    @Autowired PaymentService paymentService;
    @Autowired NotificationService notificationService;
    @Autowired LocalChannelSimulator simulator;

    @Test
    void same_notification_fired_concurrently_applies_exactly_once() throws Exception {
        PaymentOrder order = paymentService.createOrder(new CreateOrderRequest(
                "MCH-C", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 5_000L));
        PaymentNotification n = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "CONC-DUP");

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger idempotent = new AtomicInteger();
        List<NotificationResult> results = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    NotificationResult r = notificationService.handle(n);
                    results.add(r);
                    if (r.outcome() == NotificationOutcome.APPLIED) {
                        applied.incrementAndGet();
                    } else if (r.outcome() == NotificationOutcome.IDEMPOTENT) {
                        idempotent.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, applied.get(), "exactly one APPLIED");
        assertEquals(threads - 1, idempotent.get());
        assertEquals(PaymentStatus.SUCCESS, paymentService.getOrder(order.getOrderId()).getStatus());
    }

    @Test
    void conflicting_notifications_race_only_legal_one_takes_effect() throws Exception {
        // 成功与失败通知并发到达 CREATED 订单：恰一个生效，另一个被状态机拒绝
        PaymentOrder order = paymentService.createOrder(new CreateOrderRequest(
                "MCH-C", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 8_000L));
        PaymentNotification success = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "CONC-S");
        PaymentNotification failed = simulator.buildNotification(
                order, NotificationType.PAYMENT_FAILED, "CONC-F");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger idempotent = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            PaymentNotification pick = i % 2 == 0 ? success : failed;
            pool.submit(() -> {
                try {
                    start.await();
                    NotificationResult r = notificationService.handle(pick);
                    if (r.outcome() == NotificationOutcome.APPLIED) {
                        applied.incrementAndGet();
                    } else if (r.outcome() == NotificationOutcome.IDEMPOTENT) {
                        idempotent.incrementAndGet();
                    } else if (r.outcome() == NotificationOutcome.REJECTED
                            && r.rejectReason() == RejectReason.INVALID_STATUS_TRANSITION) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, applied.get(), "only one terminal transition");
        assertEquals(1, rejected.get(), "the losing notification rejected once by state machine");
        assertEquals(threads - 2, idempotent.get(), "repeated ids become idempotent");
        PaymentStatus finalStatus = paymentService.getOrder(order.getOrderId()).getStatus();
        assertTrue(finalStatus == PaymentStatus.SUCCESS || finalStatus == PaymentStatus.FAILED);
    }

    @Test
    void same_notification_id_for_two_orders_cannot_be_applied_twice() throws Exception {
        PaymentOrder a = paymentService.createOrder(new CreateOrderRequest(
                "MCH-C", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 1_000L));
        PaymentOrder b = paymentService.createOrder(new CreateOrderRequest(
                "MCH-C", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 2_000L));
        PaymentNotification na = simulator.buildNotification(
                a, NotificationType.PAYMENT_SUCCESS, "SHARED-ID");
        // 同通知ID、不同订单（内容按 b 重新签名）
        PaymentNotification nb = simulator.buildNotification(
                b, NotificationType.PAYMENT_SUCCESS, "SHARED-ID");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch doneLatch = new CountDownLatch(2);
        for (PaymentNotification pick : List.of(na, nb)) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        var r = notificationService.handle(pick);
                        if (r.outcome() == NotificationOutcome.APPLIED) {
                            applied.incrementAndGet();
                        }
                    } catch (com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException e) {
                        if (e.getReason() == RejectReason.DUPLICATE_NOTIFICATION) {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, applied.get());
        assertEquals(1, rejected.get(), "second use of shared notificationId is rejected");
    }
}
