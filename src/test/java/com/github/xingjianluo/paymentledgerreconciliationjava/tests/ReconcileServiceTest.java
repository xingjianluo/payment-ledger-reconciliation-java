package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ChannelFlow;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileDiff;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileResolution;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.CreateOrderRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileDiffView;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileReport;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ResolveDiffRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.ReconcileRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LocalChannelSimulator;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.ReconcileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 对账：四类差异可区分、重跑稳定、处理结论不被静默覆盖 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReconcileServiceTest {

    private static final String DATE = "2026-09-30";

    @Autowired PaymentService paymentService;
    @Autowired NotificationService notificationService;
    @Autowired ReconcileService reconcileService;
    @Autowired LocalChannelSimulator simulator;
    @Autowired ReconcileRepository reconcileRepository;

    private PaymentOrder order(String merchant, long amount, NotificationType terminal,
                               String successNotif, String terminalNotif) {
        PaymentOrder o = paymentService.createOrder(new CreateOrderRequest(
                merchant, "ACC-CNY-001", "MOCK_ALIPAY", "CNY", amount));
        notificationService.handle(simulator.buildNotification(
                o, NotificationType.PAYMENT_SUCCESS, successNotif));
        if (terminal != null) {
            notificationService.handle(simulator.buildNotification(
                    o, terminal, terminalNotif));
        }
        return paymentService.getOrder(o.getOrderId());
    }

    private ReconcileRequest request(List<String> csvLines) {
        return new ReconcileRequest("MOCK_ALIPAY", DATE, csvLines);
    }

    private String csv(PaymentOrder o, String status, long amount, String currency) {
        ChannelFlow flow = simulator.buildFlow(o, status, "FLW-" + o.getOrderId());
        flow.setAmount(amount);
        flow.setCurrency(currency);
        return simulator.flowToCsv(flow);
    }

    private String csvForUnknownOrder(String orderId) {
        return String.join(",", "FLW-UNKNOWN", orderId, "MOCK_ALIPAY", "MCH-R",
                "CNY", "1000", "SUCCESS", Instant.parse("2026-09-30T10:00:00Z").toString());
    }

    private Map<String, ReconcileDiffView> diffMap(ReconcileReport report) {
        return report.diffs().stream().collect(Collectors.toMap(
                d -> d.diffKey().split(":")[2], Function.identity()));
    }

    @Test
    void detect_all_four_diff_types() {
        PaymentOrder match = order("MCH-R", 1000, null, "R-S1", null);
        PaymentOrder amountDiff = order("MCH-R", 2000, null, "R-S2", null);
        PaymentOrder currencyDiff = order("MCH-R", 3000, null, "R-S3", null);
        PaymentOrder statusDiff = order("MCH-R", 4000, NotificationType.REFUND, "R-S4", "R-S4R");
        PaymentOrder extraLocal = order("MCH-R", 5000, null, "R-S5", null);

        List<String> lines = List.of(
                csv(match, "SUCCESS", 1000, "CNY"),
                csv(amountDiff, "SUCCESS", 2099, "CNY"),
                csv(currencyDiff, "SUCCESS", 3000, "USD"),
                csv(statusDiff, "SUCCESS", 4000, "CNY"),
                csvForUnknownOrder("ORD-RECON-MISSING"));
        // extraLocal 不出现在渠道流水 => EXTRA_LOCAL

        ReconcileReport report = reconcileService.reconcile(request(lines));
        Map<String, ReconcileDiffView> diffs = diffMap(report);

        assertEquals(5, report.diffCount());
        assertEquals(DiffType.AMOUNT_MISMATCH, diffs.get(amountDiff.getOrderId()).type());
        assertEquals(DiffType.CURRENCY_MISMATCH, diffs.get(currencyDiff.getOrderId()).type());
        assertEquals(DiffType.STATUS_MISMATCH, diffs.get(statusDiff.getOrderId()).type());
        assertEquals(DiffType.EXTRA_LOCAL, diffs.get(extraLocal.getOrderId()).type());
        assertEquals(DiffType.MISSING_LOCAL, diffs.get("ORD-RECON-MISSING").type());
        assertFalse(diffs.containsKey(match.getOrderId()), "matched order must not appear");
    }

    @Test
    void rerun_with_same_input_is_stable() {
        PaymentOrder o = order("MCH-R2", 1000, null, "RR-1", null);
        List<String> lines = List.of(
                csv(o, "FAILED", 1000, "CNY"),
                csvForUnknownOrder("ORD-RR-MISSING"));

        ReconcileReport first = reconcileService.reconcile(request(lines));
        ReconcileReport second = reconcileService.reconcile(request(lines));

        assertEquals(first.diffCount(), second.diffCount());
        List<String> k1 = first.diffs().stream().map(d -> d.diffKey() + ":" + d.type()).sorted().toList();
        List<String> k2 = second.diffs().stream().map(d -> d.diffKey() + ":" + d.type()).sorted().toList();
        assertEquals(k1, k2);
        // 重跑累加 seenCount，firstSeenAt 保留不变
        String key = "MOCK_ALIPAY:" + DATE + ":" + o.getOrderId();
        ReconcileDiff stored = reconcileRepository.findDiffByKey(key).orElseThrow();
        assertTrue(stored.getSeenCount() >= 2);
        assertNotNull(stored.getFirstSeenAt());
    }

    @Test
    void diff_that_disappears_on_rerun_is_inactive_but_history_kept() {
        PaymentOrder o = order("MCH-R3", 1000, null, "RD-1", null);
        ReconcileReport first = reconcileService.reconcile(request(List.of(
                csv(o, "FAILED", 1000, "CNY"))));
        String key = "MOCK_ALIPAY:" + DATE + ":" + o.getOrderId();
        assertEquals(1, first.diffCount());

        // 第二次对账渠道状态修正为一致：差异消失
        ReconcileReport second = reconcileService.reconcile(request(List.of(
                csv(o, "SUCCESS", 1000, "CNY"))));
        assertEquals(0, second.diffCount());

        ReconcileDiff stored = reconcileRepository.findDiffByKey(key).orElseThrow();
        assertFalse(stored.isActive(), "stale open diff deactivated");
        assertEquals(DiffStatus.OPEN, stored.getStatus());
    }

    @Test
    void resolving_diff_is_appended_and_never_silently_overwritten() {
        PaymentOrder o = order("MCH-R4", 1000, null, "RX-1", null);
        reconcileService.reconcile(request(List.of(csv(o, "FAILED", 1000, "CNY"))));
        String key = "MOCK_ALIPAY:" + DATE + ":" + o.getOrderId();

        ReconcileDiff resolved = reconcileService.resolve(key,
                new ResolveDiffRequest("MANUAL_ADJUST", "channel lag confirmed", "op-zhang"));
        assertEquals(DiffStatus.RESOLVED, resolved.getStatus());
        ReconcileResolution resolution = reconcileRepository.findResolution(key).orElseThrow();
        assertEquals("MANUAL_ADJUST", resolution.getAction());
        assertEquals("op-zhang", resolution.getOperator());

        // 再次处理同一差异必须被拒绝，不能静默覆盖结论
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reconcileService.resolve(key,
                        new ResolveDiffRequest("OVERWRITE", "try overwrite", "op-li")));
        assertEquals(RejectReason.DIFF_ALREADY_RESOLVED, ex.getReason());
        assertEquals("MANUAL_ADJUST",
                reconcileRepository.findResolution(key).orElseThrow().getAction());

        // 重跑对账：已处理结论保留，不被重置为 OPEN
        reconcileService.reconcile(request(List.of(csv(o, "FAILED", 1000, "CNY"))));
        assertEquals(DiffStatus.RESOLVED,
                reconcileRepository.findDiffByKey(key).orElseThrow().getStatus());
    }

    @Test
    void resolve_unknown_diff_returns_distinguishable_reason() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reconcileService.resolve("MOCK_ALIPAY:2026-09-30:ORD-GHOST",
                        new ResolveDiffRequest("X", null, null)));
        assertEquals(RejectReason.DIFF_NOT_FOUND, ex.getReason());
    }

    @Test
    void csv_from_wrong_channel_is_rejected() {
        String line = String.join(",", "F1", "ORD-Z", "MOCK_WECHAT", "MCH-R",
                "CNY", "1000", "SUCCESS", Instant.parse("2026-09-30T10:00:00Z").toString());
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reconcileService.reconcile(request(List.of(line))));
        assertEquals(RejectReason.SOURCE_MISMATCH, ex.getReason());
    }
}
