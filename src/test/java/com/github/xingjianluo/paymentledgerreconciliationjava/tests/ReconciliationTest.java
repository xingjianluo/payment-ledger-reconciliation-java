package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.*;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.ReconciliationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.simulator.LocalPaymentSimulator;
import com.github.xingjianluo.paymentledgerreconciliationjava.support.PaymentBusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 对账差异判定、重复执行稳定性与差异处理历史保护。 */
class ReconciliationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired private PaymentService paymentService;
    @org.springframework.beans.factory.annotation.Autowired private NotificationService notificationService;
    @org.springframework.beans.factory.annotation.Autowired private ReconciliationService reconciliationService;
    @org.springframework.beans.factory.annotation.Autowired private LocalPaymentSimulator simulator;

    private ChannelTransaction tx(String orderNo, long amount, String ccy, String status) {
        ChannelTransaction t = new ChannelTransaction();
        t.setOrderNo(orderNo);
        t.setMerchantId(MERCHANT);
        t.setChannel(CHANNEL);
        t.setAmount(amount);
        t.setCurrency(ccy);
        t.setStatus(status);
        return t;
    }

    private void paySuccess(String orderNo, long amount, String ccy) {
        paymentService.createOrder(orderNo, MERCHANT, CHANNEL, amount, ccy, API_KEY);
        notificationService.handle(simulator.notification("N-" + orderNo, orderNo,
                MERCHANT, CHANNEL, NotificationType.SUCCESS, amount, ccy), API_KEY);
    }

    private Map<String, ReconciliationDetail> index(ReconciliationBatch batch) {
        return batch.getDetails().stream()
                .collect(Collectors.toMap(ReconciliationDetail::getOrderNo, Function.identity()));
    }

    @Test
    void distinguishes_all_four_discrepancy_types_plus_matched() {
        paySuccess("R-MATCH", 1000L, "CNY");    // 完全一致
        paySuccess("R-AMT", 1000L, "CNY");      // 渠道金额 1200 -> 金额不一致
        paySuccess("R-STAT", 1000L, "CNY");     // 本地成功，渠道记 FAIL -> 状态不一致
        paySuccess("R-LOCAL", 1000L, "CNY");    // 本地有，渠道无 -> 多单 CHANNEL_MISSING
        // 渠道有 R-CHAN，本地无 -> 漏单 LOCAL_MISSING

        List<ChannelTransaction> rows = List.of(
                tx("R-MATCH", 1000L, "CNY", "SUCCESS"),
                tx("R-AMT", 1200L, "CNY", "SUCCESS"),
                tx("R-STAT", 1000L, "CNY", "FAIL"),
                tx("R-CHAN", 1000L, "CNY", "SUCCESS"));

        ReconciliationBatch batch = reconciliationService.importAndRun("B1", CHANNEL, rows);
        Map<String, ReconciliationDetail> d = index(batch);

        assertEquals(DiscrepancyType.MATCHED, d.get("R-MATCH").getDiscrepancyType());
        assertEquals(DiscrepancyType.AMOUNT_MISMATCH, d.get("R-AMT").getDiscrepancyType());
        assertEquals(DiscrepancyType.STATUS_MISMATCH, d.get("R-STAT").getDiscrepancyType());
        assertEquals(DiscrepancyType.CHANNEL_MISSING, d.get("R-LOCAL").getDiscrepancyType());
        assertEquals(DiscrepancyType.LOCAL_MISSING, d.get("R-CHAN").getDiscrepancyType());

        // 非 MATCHED 的差异均为待处理
        assertEquals(DiscrepancyStatus.OPEN, d.get("R-AMT").getDiscrepancyStatus());
        assertEquals(DiscrepancyStatus.OPEN, d.get("R-CHAN").getDiscrepancyStatus());
        assertEquals(4, batch.getDetails().stream()
                .filter(x -> x.getDiscrepancyType() != DiscrepancyType.MATCHED).count());
    }

    @Test
    void rerun_same_batch_is_stable_even_with_different_submitted_input() {
        paySuccess("R-RUN", 1000L, "CNY");
        List<ChannelTransaction> first = List.of(tx("R-RUN", 1000L, "CNY", "SUCCESS"));
        List<ChannelTransaction> second = List.of(
                tx("R-RUN", 9999L, "CNY", "FAIL"),
                tx("R-INTRUDER", 1L, "CNY", "SUCCESS"));

        var b1 = reconciliationService.importAndRun("B2", CHANNEL, first);
        var b2 = reconciliationService.importAndRun("B2", CHANNEL, second);

        // 输入快照只认第一次：差异结论稳定，detailId 可能重新生成，但类型/数量一致
        assertEquals(b1.getChannelDataHash(), b2.getChannelDataHash());
        assertEquals(1, b2.getDetails().size());
        assertEquals(DiscrepancyType.MATCHED, b2.getDetails().get(0).getDiscrepancyType());

        // 再跑一次仍然完全一致（结果可重复）
        var b3 = reconciliationService.importAndRun("B2", CHANNEL, first);
        assertEquals(b2.getDetails().size(), b3.getDetails().size());
        assertEquals(DiscrepancyType.MATCHED, b3.getDetails().get(0).getDiscrepancyType());
    }

    @Test
    void handled_discrepancy_is_not_silently_overwritten_on_rerun() {
        paySuccess("R-HIST", 1000L, "CNY");
        List<ChannelTransaction> rows = List.of(tx("R-HIST", 1500L, "CNY", "SUCCESS"));
        var batch = reconciliationService.importAndRun("B3", CHANNEL, rows);
        var detail = index(batch).get("R-HIST");
        assertEquals(DiscrepancyStatus.OPEN, detail.getDiscrepancyStatus());

        // 人工处理该差异
        reconciliationService.handleDiscrepancy("B3", detail.getId(),
                DiscrepancyStatus.HANDLED, "确认渠道扣费错误，以本地为准", "alice", false);

        // 重跑：底层结论仍是金额差异，但已处理结论必须保留，不被静默改回 OPEN
        var rerun = reconciliationService.importAndRun("B3", CHANNEL, rows);
        var after = index(rerun).get("R-HIST");
        assertEquals(DiscrepancyType.AMOUNT_MISMATCH, after.getDiscrepancyType());
        assertEquals(DiscrepancyStatus.HANDLED, after.getDiscrepancyStatus());
        assertEquals("alice", after.getHandledBy());
        assertTrue(after.getHandleNote().contains("确认渠道扣费错误"));
        assertTrue(after.getHandledAt() > 0);
    }

    @Test
    void already_handled_discrepancy_cannot_be_handled_again_without_force() {
        paySuccess("R-AGAIN", 1000L, "CNY");
        var batch = reconciliationService.importAndRun("B4", CHANNEL,
                List.of(tx("R-AGAIN", 2000L, "CNY", "SUCCESS")));
        long id = index(batch).get("R-AGAIN").getId();
        reconciliationService.handleDiscrepancy("B4", id,
                DiscrepancyStatus.HANDLED, "第一次结论", "alice", false);

        PaymentBusinessException ex = assertThrows(PaymentBusinessException.class,
                () -> reconciliationService.handleDiscrepancy("B4", id,
                        DiscrepancyStatus.HANDLED, "试图静默覆盖", "bob", false));
        assertEquals(RejectReason.DETAIL_ALREADY_HANDLED, ex.getReason());

        // force=true 也不静默覆盖：历史说明以追加形式保留
        var forced = reconciliationService.handleDiscrepancy("B4", id,
                DiscrepancyStatus.IGNORED, "复核后忽略", "bob", true);
        assertEquals(DiscrepancyStatus.IGNORED, forced.getDiscrepancyStatus());
        assertTrue(forced.getHandleNote().contains("第一次结论"), "历史结论必须保留");
        assertTrue(forced.getHandleNote().contains("复核后忽略"));
        assertTrue(forced.getHandleNote().contains("强制更新"));
    }

    @Test
    void matched_item_cannot_be_handled_as_discrepancy() {
        paySuccess("R-OK", 1000L, "CNY");
        var batch = reconciliationService.importAndRun("B5", CHANNEL,
                List.of(tx("R-OK", 1000L, "CNY", "SUCCESS")));
        long id = index(batch).get("R-OK").getId();
        PaymentBusinessException ex = assertThrows(PaymentBusinessException.class,
                () -> reconciliationService.handleDiscrepancy("B5", id,
                        DiscrepancyStatus.HANDLED, "无需处理", "alice", false));
        assertEquals(RejectReason.ILLEGAL_TRANSITION, ex.getReason());
    }

    @Test
    void missing_batch_and_detail_give_distinguishable_errors() {
        PaymentBusinessException e1 = assertThrows(PaymentBusinessException.class,
                () -> reconciliationService.getBatch("NO-BATCH"));
        assertEquals(RejectReason.RECON_BATCH_NOT_FOUND, e1.getReason());

        paySuccess("R-DET", 1000L, "CNY");
        reconciliationService.importAndRun("B6", CHANNEL,
                List.of(tx("R-DET", 1000L, "CNY", "SUCCESS")));
        PaymentBusinessException e2 = assertThrows(PaymentBusinessException.class,
                () -> reconciliationService.handleDiscrepancy("B6", 999999L,
                        DiscrepancyStatus.HANDLED, "x", "alice", false));
        assertEquals(RejectReason.DETAIL_NOT_FOUND, e2.getReason());
    }
}
