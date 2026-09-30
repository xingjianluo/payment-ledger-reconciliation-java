package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LedgerService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import com.github.xingjianluo.paymentledgerreconciliationjava.simulator.LocalPaymentSimulator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 账本一致性：成功后总额守恒可追溯；失败不留下半落账、重复扣款或余额漂移。 */
class LedgerConsistencyTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired private PaymentService paymentService;
    @org.springframework.beans.factory.annotation.Autowired private NotificationService notificationService;
    @org.springframework.beans.factory.annotation.Autowired private LedgerService ledgerService;
    @org.springframework.beans.factory.annotation.Autowired private LocalPaymentSimulator simulator;

    private Map<String, Long> accountBalances() {
        Map<String, Long> m = new HashMap<>();
        for (LedgerAccount a : ledgerService.allAccounts()) {
            m.put(a.getAccountId(), a.getBalance());
        }
        return m;
    }

    @Test
    void successful_payment_balances_merchant_and_clearing_accounts() {
        paymentService.createOrder("O-L1", MERCHANT, CHANNEL, 12345L, "CNY", API_KEY);
        var r = notificationService.handle(simulator.notification("N-L1", "O-L1",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 12345L, "CNY"), API_KEY);
        assertTrue(r.accepted());

        Map<String, Long> bal = accountBalances();
        assertEquals(12345L, bal.get("MERCHANT:" + MERCHANT + ":CNY"), "商户应收增加");
        assertEquals(-12345L, bal.get("CLEARING:" + CHANNEL + ":CNY"), "渠道清算方向相反");
        assertTrue(ledgerService.isConserved());
    }

    @Test
    void multiple_currencies_are_separately_conserved() {
        paymentService.createOrder("O-C1", MERCHANT, CHANNEL, 1000L, "CNY", API_KEY);
        paymentService.createOrder("O-C2", MERCHANT, CHANNEL, 2000L, "USD", API_KEY);
        notificationService.handle(simulator.notification("N-C1", "O-C1",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 1000L, "CNY"), API_KEY);
        notificationService.handle(simulator.notification("N-C2", "O-C2",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 2000L, "USD"), API_KEY);

        Map<String, Long> bal = accountBalances();
        assertEquals(1000L, bal.get("MERCHANT:" + MERCHANT + ":CNY"));
        assertEquals(2000L, bal.get("MERCHANT:" + MERCHANT + ":USD"));
        assertEquals(-1000L, bal.get("CLEARING:" + CHANNEL + ":CNY"));
        assertEquals(-2000L, bal.get("CLEARING:" + CHANNEL + ":USD"));
        assertTrue(ledgerService.isConserved(), "每个币种内部各自借贷相等");
    }

    @Test
    void failed_posting_leaves_no_partial_entries_and_no_balance_drift() {
        paymentService.createOrder("O-FAULT", MERCHANT, CHANNEL, 7777L, "CNY", API_KEY);

        // 过账前记录：无账户、无分录
        assertTrue(ledgerService.allAccounts().isEmpty());
        assertEquals(0, ledgerService.allEntries().size());

        faultInjector.failNextPost();
        var r = notificationService.handle(simulator.notification("N-FAULT", "O-FAULT",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 7777L, "CNY"), API_KEY);
        assertFalse(r.accepted(), "注入故障后通知必须失败");
        assertEquals("LEDGER_POSTING_FAILED", r.reason());

        // 关键断言：没有半条分录、没有任何账户余额产生、单据状态仍为 CREATED
        assertEquals(0, ledgerService.allEntries().size(), "失败后不得留下任何分录");
        assertTrue(ledgerService.allAccounts().isEmpty()
                        || ledgerService.allAccounts().stream().allMatch(a -> a.getBalance() == 0),
                "失败后不得产生余额漂移");
        assertEquals(PaymentStatus.CREATED, paymentService.getOrder("O-FAULT").getStatus());
        assertTrue(ledgerService.isConserved());

        // 故障解除后同一通知可重试成功，且只产生一组分录
        var retry = notificationService.handle(simulator.notification("N-FAULT", "O-FAULT",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 7777L, "CNY"), API_KEY);
        assertTrue(retry.accepted());
        assertEquals(2, ledgerService.entriesOfOrder("O-FAULT").size());
        assertEquals(PaymentStatus.SUCCESS, paymentService.getOrder("O-FAULT").getStatus());
        assertTrue(ledgerService.isConserved());
    }

    @Test
    void refund_returns_balances_to_zero_without_double_debit() {
        paymentService.createOrder("O-RB", MERCHANT, CHANNEL, 5000L, "CNY", API_KEY);
        notificationService.handle(simulator.notification("N-RB-1", "O-RB",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 5000L, "CNY"), API_KEY);
        notificationService.handle(simulator.notification("N-RB-2", "O-RB",
                MERCHANT, CHANNEL, NotificationType.REFUND, 5000L, "CNY"), API_KEY);

        // 第二次退款（终态 REFUNDED 后）必须被拒，不能重复扣款
        var secondRefund = notificationService.handle(simulator.notification("N-RB-3", "O-RB",
                MERCHANT, CHANNEL, NotificationType.REFUND, 5000L, "CNY"), API_KEY);
        assertFalse(secondRefund.accepted());

        List<com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerEntry> entries =
                ledgerService.entriesOfOrder("O-RB");
        assertEquals(4, entries.size(), "只允许入账+退款各一组");
        assertEquals(0L, accountBalances().get("MERCHANT:" + MERCHANT + ":CNY"));
        assertEquals(0L, accountBalances().get("CLEARING:" + CHANNEL + ":CNY"));
        assertTrue(ledgerService.isConserved());
    }

    @Test
    void every_entry_pair_is_traceable_by_order_and_notification() {
        paymentService.createOrder("O-TRACE", MERCHANT, CHANNEL, 4242L, "CNY", API_KEY);
        notificationService.handle(simulator.notification("N-TRACE", "O-TRACE",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 4242L, "CNY"), API_KEY);

        var entries = ledgerService.entriesOfOrder("O-TRACE");
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> e.orderNo().equals("O-TRACE")));
        assertTrue(entries.stream().allMatch(e -> e.notificationId().equals("N-TRACE")));
        assertNotNull(entries.get(0).postedAt());
    }
}
