package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.LedgerEntryType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationOutcome;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.Account;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.CreateOrderRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.LedgerReport;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.AccountRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.LedgerEntryRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LedgerQueryService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LedgerService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LocalChannelSimulator;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

/** 账本：成功后总额守恒；入账失败不留部分落账、不重复扣款 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LedgerConsistencyTest {

    @Autowired PaymentService paymentService;
    @Autowired NotificationService notificationService;
    @Autowired LocalChannelSimulator simulator;
    @Autowired LedgerService ledgerService;
    @Autowired LedgerQueryService ledgerQueryService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository entryRepository;

    private long balanceOf(String accountId) {
        return accountRepository.findById(accountId).map(Account::getBalance).orElseThrow();
    }

    @Test
    void payment_refund_reversal_keeps_ledger_conservation() {
        long startCny = balanceOf("ACC-CNY-001");

        PaymentOrder pay = paymentService.createOrder(new CreateOrderRequest(
                "MCH-L", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 30_000L));
        notificationService.handle(simulator.buildNotification(
                pay, NotificationType.PAYMENT_SUCCESS, "L-1"));
        notificationService.handle(simulator.buildNotification(
                pay, NotificationType.REFUND, "L-2"));

        PaymentOrder pay2 = paymentService.createOrder(new CreateOrderRequest(
                "MCH-L", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 5_000L));
        notificationService.handle(simulator.buildNotification(
                pay2, NotificationType.PAYMENT_SUCCESS, "L-3"));
        notificationService.handle(simulator.buildNotification(
                pay2, NotificationType.REVERSAL, "L-4"));

        long endCny = balanceOf("ACC-CNY-001");
        assertEquals(startCny, endCny, "fully refunded + reversed => balance unchanged");

        LedgerReport report = ledgerQueryService.report();
        long accountSum = report.accounts().stream()
                .mapToLong(AccountView -> AccountView.balance()).sum();
        long entriesSum = report.sumSignedEntries();
        assertEquals(accountSum - 2_000_000L, entriesSum,
                "sum(signed entries) must equal sum(balances) minus initial deposits");
    }

    @Test
    void idempotent_post_applies_once() {
        long before = balanceOf("ACC-CNY-001");
        ledgerService.post("ORD-IDEM", "ACC-CNY-001", "CNY", 1_000L,
                LedgerEntryType.PAYMENT_IN, "KEY-IDEM-1");
        ledgerService.post("ORD-IDEM", "ACC-CNY-001", "CNY", 1_000L,
                LedgerEntryType.PAYMENT_IN, "KEY-IDEM-1");
        ledgerService.post("ORD-IDEM", "ACC-CNY-001", "CNY", 1_000L,
                LedgerEntryType.PAYMENT_IN, "KEY-IDEM-1");
        assertEquals(before + 1_000L, balanceOf("ACC-CNY-001"));
        assertEquals(1, entryRepository.findByOrderId("ORD-IDEM").size());
    }

    @Test
    void failed_post_leaves_no_partial_effect() {
        PaymentOrder order = paymentService.createOrder(new CreateOrderRequest(
                "MCH-L", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 12_345L));
        long balanceBefore = balanceOf(order.getAccountId());
        int entriesBefore = entryRepository.findAll().size();

        ledgerService.injectFailureForNextPost(order.getOrderId());
        BusinessException ex = assertThrows(BusinessException.class, () ->
                ledgerService.post(order.getOrderId(), order.getAccountId(), "CNY",
                        order.getAmount(), LedgerEntryType.PAYMENT_IN, "KEY-FAIL-1"));
        assertEquals("LEDGER_POST_FAILED", ex.getReason().name());

        assertEquals(balanceBefore, balanceOf(order.getAccountId()), "no balance drift");
        assertEquals(entriesBefore, entryRepository.findAll().size(), "no ledger entry left");
    }

    @Test
    void notification_failed_by_ledger_does_not_change_order_and_retriable() {
        PaymentOrder order = paymentService.createOrder(new CreateOrderRequest(
                "MCH-L", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 7_777L));
        long balanceBefore = balanceOf(order.getAccountId());
        ledgerService.injectFailureForNextPost(order.getOrderId());

        // 入账异常冒泡，订单未更新，通知未标记 APPLIED
        assertThrows(BusinessException.class, () -> notificationService.handle(
                simulator.buildNotification(order, NotificationType.PAYMENT_SUCCESS, "L-FAIL")));
        assertEquals(PaymentStatus.CREATED, paymentService.getOrder(order.getOrderId()).getStatus());
        assertEquals(balanceBefore, balanceOf(order.getAccountId()));

        // 故障解除后同一通知重试成功
        NotificationResultRetryHolder holder = new NotificationResultRetryHolder();
        holder.result = notificationService.handle(simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "L-FAIL"));
        assertEquals(NotificationOutcome.APPLIED, holder.result.outcome());
        assertEquals(balanceBefore + 7_777L, balanceOf(order.getAccountId()));
    }

    @Test
    void debit_larger_than_balance_is_rejected() {
        Account small = new Account();
        small.setAccountId("ACC-TINY");
        small.setCurrency("CNY");
        small.setBalance(10L);
        small.setVersion(0);
        accountRepository.save(small);

        long before = balanceOf("ACC-TINY");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                ledgerService.post("ORD-X", "ACC-TINY", "CNY", 100L,
                        LedgerEntryType.REFUND_OUT, "KEY-NSF-1"));
        assertEquals("LEDGER_POST_FAILED", ex.getReason().name());
        assertEquals(before, balanceOf("ACC-TINY"));
    }

    private static final class NotificationResultRetryHolder {
        com.github.xingjianluo.paymentledgerreconciliationjava.dto.NotificationResult result;
    }
}
