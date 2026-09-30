package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.*;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.ChannelDataSnapshotRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.PaymentOrderRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.ReconciliationRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.support.PaymentBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 渠道对账：导入流水、执行比对（可重复、结论稳定）、处理差异（不静默覆盖历史）。 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository reconciliationRepository;
    private final ChannelDataSnapshotRepository snapshotRepository;
    private final PaymentOrderRepository orderRepository;

    public ReconciliationService(ReconciliationRepository reconciliationRepository,
                                 ChannelDataSnapshotRepository snapshotRepository,
                                 PaymentOrderRepository orderRepository) {
        this.reconciliationRepository = reconciliationRepository;
        this.snapshotRepository = snapshotRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * 导入渠道流水并执行对账，可重复调用同一 batchId：
     * 输入在首次运行时固化为快照，之后重跑一律基于同一快照重新计算，结论稳定；
     * 已被人工处理（HANDLED/IGNORED）的差异不会被静默覆盖。
     */
    public ReconciliationBatch importAndRun(String batchId, String channel,
                                            List<ChannelTransaction> channelTransactions) {
        if (batchId == null || batchId.isBlank()) {
            throw new PaymentBusinessException(RejectReason.DETAIL_NOT_FOUND, "batchId 不能为空");
        }
        synchronized (reconciliationRepository.repoLock()) {
            // 1) 固化输入（仅首次生效），重跑忽略后续传入内容，保证结果稳定
            List<ChannelTransaction> snapshot = snapshotRepository.saveIfAbsent(batchId, channelTransactions);

            ReconciliationBatch batch = reconciliationRepository.findById(batchId).orElseGet(() -> {
                ReconciliationBatch b = new ReconciliationBatch();
                b.setBatchId(batchId);
                b.setChannel(channel);
                b.setStatus(BatchStatus.OPEN);
                b.setChannelDataHash(Integer.toHexString(snapshot.hashCode()));
                b.setCreatedAt(System.currentTimeMillis());
                return b;
            });

            // 2) 以 orderNo 为键，用本次计算结果构建结论表
            Map<String, ReconciliationDetail> previousByOrder = new LinkedHashMap<>();
            for (ReconciliationDetail d : batch.getDetails()) {
                previousByOrder.put(d.getOrderNo(), d);
            }

            List<ReconciliationDetail> freshDetails = new ArrayList<>();
            Map<String, PaymentOrder> localByOrder = new LinkedHashMap<>();
            for (PaymentOrder o : orderRepository.findByChannel(channel)) {
                localByOrder.put(o.getOrderNo(), o);
            }

            // 3) 遍历渠道流水：命中本地单则比对，未命中即漏单（LOCAL_MISSING）
            for (ChannelTransaction tx : snapshot) {
                PaymentOrder local = localByOrder.remove(tx.getOrderNo());
                ReconciliationDetail d = compare(batchId, tx, local);
                preserveHistory(d, previousByOrder.remove(d.getOrderNo()));
                freshDetails.add(d);
            }
            // 4) 本地剩余单据：渠道没有对应流水，即多单（CHANNEL_MISSING）
            for (PaymentOrder local : localByOrder.values()) {
                ReconciliationDetail d = localOnlyDetail(batchId, local);
                preserveHistory(d, previousByOrder.remove(d.getOrderNo()));
                freshDetails.add(d);
            }
            // 5) 上一轮有、本轮计算消失的键不应出现（同一快照），保险起见仍保留历史结论不丢弃
            for (ReconciliationDetail leftover : previousByOrder.values()) {
                freshDetails.add(leftover);
                log.warn("RECON {} orderNo={} 保留上一轮残留结论 type={} status={}",
                        batchId, leftover.getOrderNo(), leftover.getDiscrepancyType(),
                        leftover.getDiscrepancyStatus());
            }

            freshDetails.sort((a, b) -> a.getOrderNo().compareTo(b.getOrderNo()));
            batch.getDetails().clear();
            batch.getDetails().addAll(freshDetails);
            reconciliationRepository.save(batch);

            long open = freshDetails.stream()
                    .filter(d -> d.getDiscrepancyStatus() == DiscrepancyStatus.OPEN).count();
            log.info("RECON DONE batchId={} channel={} rows={} open差异={} (MATCHED不计差异)",
                    batchId, channel, snapshot.size(), open);
            return batch;
        }
    }

    private ReconciliationDetail compare(String batchId, ChannelTransaction tx, PaymentOrder local) {
        ReconciliationDetail d = baseDetail(batchId, tx.getOrderNo());
        d.setChannelAmount(tx.getAmount());
        d.setChannelCurrency(tx.getCurrency());
        d.setChannelStatus(tx.getStatus());
        if (local == null) {
            d.setDiscrepancyType(DiscrepancyType.LOCAL_MISSING);
            d.setDiscrepancyStatus(DiscrepancyStatus.OPEN);
            return d;
        }
        fillLocal(d, local);
        // 金额不一致优先于状态不一致，原因必须可区分
        if (local.getAmount() != tx.getAmount()) {
            d.setDiscrepancyType(DiscrepancyType.AMOUNT_MISMATCH);
            d.setDiscrepancyStatus(DiscrepancyStatus.OPEN);
        } else if (!local.getCurrency().equals(tx.getCurrency())) {
            d.setDiscrepancyType(DiscrepancyType.AMOUNT_MISMATCH);
            d.setDiscrepancyStatus(DiscrepancyStatus.OPEN);
        } else if (!channelStatusMatches(local.getStatus(), tx.getStatus())) {
            d.setDiscrepancyType(DiscrepancyType.STATUS_MISMATCH);
            d.setDiscrepancyStatus(DiscrepancyStatus.OPEN);
        } else {
            d.setDiscrepancyType(DiscrepancyType.MATCHED);
            d.setDiscrepancyStatus(DiscrepancyStatus.HANDLED);
        }
        return d;
    }

    private ReconciliationDetail localOnlyDetail(String batchId, PaymentOrder local) {
        ReconciliationDetail d = baseDetail(batchId, local.getOrderNo());
        fillLocal(d, local);
        d.setDiscrepancyType(DiscrepancyType.CHANNEL_MISSING);
        d.setDiscrepancyStatus(DiscrepancyStatus.OPEN);
        return d;
    }

    /** 渠道状态（SUCCESS/FAIL/REFUND/REVERSAL）与本地状态机状态的等价映射。 */
    private boolean channelStatusMatches(PaymentStatus local, String channelStatus) {
        if (channelStatus == null) {
            return false;
        }
        return switch (channelStatus.trim().toUpperCase()) {
            case "SUCCESS", "PAID" -> local == PaymentStatus.SUCCESS;
            case "FAIL", "FAILED", "CLOSED" -> local == PaymentStatus.FAILED;
            case "REFUND", "REFUNDED" -> local == PaymentStatus.REFUNDED;
            case "REVERSAL", "REVERSED" -> local == PaymentStatus.REVERSED;
            default -> false;
        };
    }

    private ReconciliationDetail baseDetail(String batchId, String orderNo) {
        ReconciliationDetail d = new ReconciliationDetail();
        d.setId(reconciliationRepository.nextDetailId());
        d.setBatchId(batchId);
        d.setOrderNo(orderNo);
        d.setCreatedAt(System.currentTimeMillis());
        return d;
    }

    private void fillLocal(ReconciliationDetail d, PaymentOrder local) {
        d.setLocalAmount(local.getAmount());
        d.setLocalCurrency(local.getCurrency());
        d.setLocalStatus(local.getStatus());
    }

    /**
     * 历史保护：新一轮计算结果为 OPEN 时，若该差异已被人工处理，则保留历史结论与处理痕迹，
     * 绝不用新的 OPEN 静默覆盖；MATCHED 等非差异项也不回滚已有的人工结论。
     */
    private void preserveHistory(ReconciliationDetail fresh, ReconciliationDetail previous) {
        if (previous == null) {
            return;
        }
        if (previous.getDiscrepancyStatus() == DiscrepancyStatus.HANDLED
                || previous.getDiscrepancyStatus() == DiscrepancyStatus.IGNORED) {
            if (fresh.getDiscrepancyStatus() == DiscrepancyStatus.OPEN) {
                log.warn("RECON 历史结论保护 orderNo={} 保持 {}（新一轮计算为 {}），不静默覆盖",
                        fresh.getOrderNo(), previous.getDiscrepancyStatus(),
                        fresh.getDiscrepancyType());
            }
            fresh.setDiscrepancyStatus(previous.getDiscrepancyStatus());
            fresh.setHandleNote(previous.getHandleNote());
            fresh.setHandledBy(previous.getHandledBy());
            fresh.setHandledAt(previous.getHandledAt());
        }
    }

    public ReconciliationBatch getBatch(String batchId) {
        return reconciliationRepository.findById(batchId)
                .orElseThrow(() -> new PaymentBusinessException(RejectReason.RECON_BATCH_NOT_FOUND,
                        "对账批次不存在: " + batchId));
    }

    public List<ReconciliationBatch> listBatches() {
        return reconciliationRepository.findAll();
    }

    /**
     * 处理一条差异：只能处理 OPEN 差异；已处理的差异默认拒绝（DETAIL_ALREADY_HANDLED），
     * force=true 时也不静默覆盖，而是追加处理说明并保留操作人轨迹。
     */
    public ReconciliationDetail handleDiscrepancy(String batchId, long detailId,
                                                  DiscrepancyStatus status, String note,
                                                  String operator, boolean force) {
        synchronized (reconciliationRepository.repoLock()) {
            ReconciliationBatch batch = getBatch(batchId);
            ReconciliationDetail detail = batch.getDetails().stream()
                    .filter(d -> d.getId() == detailId)
                    .findFirst()
                    .orElseThrow(() -> new PaymentBusinessException(RejectReason.DETAIL_NOT_FOUND,
                            "对账明细不存在: batchId=" + batchId + " detailId=" + detailId));
            if (detail.getDiscrepancyType() == DiscrepancyType.MATCHED) {
                throw new PaymentBusinessException(RejectReason.ILLEGAL_TRANSITION,
                        "MATCHED 项不是差异，无需处理: orderNo=" + detail.getOrderNo());
            }
            if (detail.getDiscrepancyStatus() != DiscrepancyStatus.OPEN && !force) {
                throw new PaymentBusinessException(RejectReason.DETAIL_ALREADY_HANDLED,
                        "差异已处理，不能静默覆盖: orderNo=" + detail.getOrderNo()
                                + " status=" + detail.getDiscrepancyStatus());
            }
            String previousNote = detail.getHandleNote();
            String appended = (previousNote == null || previousNote.isBlank() ? "" : previousNote + " | ")
                    + "[" + (operator == null ? "unknown" : operator) + "] " + (note == null ? "" : note)
                    + (force && detail.getDiscrepancyStatus() != DiscrepancyStatus.OPEN ? "（强制更新，历史已保留）" : "");
            detail.setDiscrepancyStatus(status == null ? DiscrepancyStatus.HANDLED : status);
            detail.setHandleNote(appended);
            detail.setHandledBy(operator);
            detail.setHandledAt(System.currentTimeMillis());
            reconciliationRepository.save(batch);
            log.info("RECON HANDLE batchId={} detailId={} orderNo={} type={} -> {} operator={} force={}",
                    batchId, detailId, detail.getOrderNo(), detail.getDiscrepancyType(),
                    detail.getDiscrepancyStatus(), operator, force);
            return detail;
        }
    }
}
