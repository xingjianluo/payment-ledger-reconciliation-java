package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.DiffType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ChannelFlow;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileBatch;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileDiff;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileResolution;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileDiffView;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileReport;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ResolveDiffRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.ChannelFlowRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.PaymentOrderRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.ReconcileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道对账（渠道流水为本地 CSV 导入，无外部依赖）。
 * 差异类型：MISSING_LOCAL（渠道有本地无/漏单）、EXTRA_LOCAL（本地有渠道无/多单）、
 * AMOUNT_MISMATCH、CURRENCY_MISMATCH、STATUS_MISMATCH。
 * 差异稳定键 = channel:reconDate:identity（identity 优先取 orderId，无订单时取渠道流水号）。
 * 同渠道同日重复执行：当批 OPEN 差异集合完全由输入数据决定，结果稳定；
 * 已消失的 OPEN 差异标记 inactive（历史记录保留可追溯），RESOLVED 结论永不被重跑覆盖。
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);
    private static final Set<PaymentStatus> TERMINAL_LOCAL =
            Set.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED,
                    PaymentStatus.REFUNDED, PaymentStatus.REVERSED);

    private final ChannelFlowRepository flowRepository;
    private final PaymentOrderRepository orderRepository;
    private final ReconcileRepository reconcileRepository;

    public ReconcileService(ChannelFlowRepository flowRepository,
                            PaymentOrderRepository orderRepository,
                            ReconcileRepository reconcileRepository) {
        this.flowRepository = flowRepository;
        this.orderRepository = orderRepository;
        this.reconcileRepository = reconcileRepository;
    }

    public ReconcileReport reconcile(ReconcileRequest request) {
        validate(request);

        flowRepository.deleteByChannelAndDate(request.channel(), request.reconDate());
        List<ChannelFlow> flows = parseCsv(request.csvLines(), request.channel());
        flows.forEach(flowRepository::save);

        Instant dayEnd = LocalDate.parse(request.reconDate()).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        List<PaymentOrder> localOrders = orderRepository.findByChannel(request.channel()).stream()
                .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isBefore(dayEnd))
                .toList();

        Map<String, ReconcileDiff> current = new LinkedHashMap<>();
        Set<String> matchedOrderIds = new HashSet<>();

        for (ChannelFlow flow : flows) {
            PaymentOrder order = orderRepository.findById(flow.getOrderId()).orElse(null);
            if (order == null) {
                put(current, buildDiff(request, flow.getOrderId(), flow.getChannelFlowId(),
                        DiffType.MISSING_LOCAL, null, flow));
                continue;
            }
            matchedOrderIds.add(order.getOrderId());
            DiffType type = classifyMatched(order, flow);
            if (type != null) {
                put(current, buildDiff(request, order.getOrderId(), flow.getChannelFlowId(),
                        type, order, flow));
            }
        }
        for (PaymentOrder order : localOrders) {
            if (!matchedOrderIds.contains(order.getOrderId())
                    && TERMINAL_LOCAL.contains(order.getStatus())) {
                put(current, buildDiff(request, order.getOrderId(), null,
                        DiffType.EXTRA_LOCAL, order, null));
            }
        }

        String batchId = "BATCH-" + request.channel() + "-" + request.reconDate()
                + "-" + Instant.now().toEpochMilli();
        reconcileStaleDiffs(request, current.keySet(), batchId);
        current.values().forEach(d -> {
            d.setBatchId(batchId);
            reconcileRepository.saveDiff(d);
        });

        ReconcileBatch batch = new ReconcileBatch();
        batch.setBatchId(batchId);
        batch.setChannel(request.channel());
        batch.setReconDate(request.reconDate());
        batch.setRanAt(Instant.now());
        batch.setTotalFlows(flows.size());
        batch.setTotalOrders(localOrders.size());
        batch.setDiffCount(current.size());
        reconcileRepository.saveBatch(batch);

        List<ReconcileDiffView> views = current.values().stream()
                .map(ReconcileService::toView)
                .sorted(Comparator.comparing(ReconcileDiffView::diffKey))
                .toList();
        log.info("reconcile done batch={} channel={} date={} flows={} orders={} diffs={}",
                batchId, request.channel(), request.reconDate(),
                flows.size(), localOrders.size(), current.size());
        return new ReconcileReport(batchId, request.channel(), request.reconDate(),
                flows.size(), localOrders.size(), current.size(), views);
    }

    /** 金额优先，其次币种，再次状态；互不掩盖，调用方可从原因码区分 */
    private DiffType classifyMatched(PaymentOrder order, ChannelFlow flow) {
        if (order.getAmount() != flow.getAmount()) {
            return DiffType.AMOUNT_MISMATCH;
        }
        if (!order.getCurrency().equals(flow.getCurrency())) {
            return DiffType.CURRENCY_MISMATCH;
        }
        if (!statusMatches(order.getStatus(), flow.getChannelStatus())) {
            return DiffType.STATUS_MISMATCH;
        }
        return null;
    }

    private boolean statusMatches(PaymentStatus local, String channelStatus) {
        if (channelStatus == null) {
            return false;
        }
        return switch (local) {
            case SUCCESS -> "SUCCESS".equals(channelStatus);
            case FAILED -> "FAILED".equals(channelStatus);
            case REFUNDED -> "REFUNDED".equals(channelStatus);
            case REVERSED -> "REVERSED".equals(channelStatus);
            case CREATED -> false;
        };
    }

    private void put(Map<String, ReconcileDiff> bag, ReconcileDiff diff) {
        bag.put(diff.getDiffKey(), diff);
    }

    private ReconcileDiff buildDiff(ReconcileRequest req, String orderId, String flowId,
                                    DiffType type, PaymentOrder order, ChannelFlow flow) {
        String identity = orderId != null ? orderId : flowId;
        String diffKey = req.channel() + ":" + req.reconDate() + ":" + identity;
        Instant now = Instant.now();
        ReconcileDiff diff = reconcileRepository.findDiffByKey(diffKey)
                .orElseGet(ReconcileDiff::new);
        if (diff.getDiffKey() == null) {
            diff.setDiffKey(diffKey);
            diff.setChannel(req.channel());
            diff.setReconDate(req.reconDate());
            diff.setOrderId(orderId);
            diff.setFirstSeenAt(now);
            diff.setSeenCount(0);
        }
        // 已处理差异的类型结论不被重跑改写；其余按本次比对结果更新
        if (diff.getStatus() != DiffStatus.RESOLVED) {
            diff.setType(type);
        }
        diff.setChannelFlowId(flowId);
        diff.setLocalAmount(order == null ? null : order.getAmount());
        diff.setChannelAmount(flow == null ? null : flow.getAmount());
        diff.setLocalCurrency(order == null ? null : order.getCurrency());
        diff.setChannelCurrency(flow == null ? null : flow.getCurrency());
        diff.setLocalStatus(order == null ? null : order.getStatus().name());
        diff.setChannelStatus(flow == null ? null : flow.getChannelStatus());
        diff.setLastSeenAt(now);
        diff.setSeenCount(diff.getSeenCount() + 1);
        diff.setActive(true);
        // RESOLVED 状态保持不变（历史结论不被重跑改写）；其余为 OPEN
        if (diff.getStatus() == null) {
            diff.setStatus(DiffStatus.OPEN);
        }
        return diff;
    }

    private void reconcileStaleDiffs(ReconcileRequest req, Set<String> currentKeys,
                                     String batchId) {
        for (ReconcileDiff existing :
                reconcileRepository.findDiffs(req.channel(), req.reconDate())) {
            if (currentKeys.contains(existing.getDiffKey())) {
                continue;
            }
            if (existing.getStatus() == DiffStatus.OPEN) {
                // 差异在新数据中已消失：停用 OPEN 项，历史字段仍保留可追溯
                existing.setActive(false);
                existing.setBatchId(batchId);
                existing.setLastSeenAt(Instant.now());
                reconcileRepository.saveDiff(existing);
            }
            // RESOLVED 差异即使不再出现也保持原样，不静默改写
        }
    }

    public ReconcileDiff resolve(String diffKey, ResolveDiffRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "action is required");
        }
        ReconcileDiff diff = reconcileRepository.findDiffByKey(diffKey)
                .orElseThrow(() -> new BusinessException(RejectReason.DIFF_NOT_FOUND,
                        "diff not found: " + diffKey));
        if (!diff.isActive()) {
            throw new BusinessException(RejectReason.DIFF_NOT_FOUND,
                    "diff is no longer present in latest reconciliation: " + diffKey);
        }
        // 已处理差异不得静默覆盖：必须先显式重新打开后才能给出新结论
        if (diff.getStatus() == DiffStatus.RESOLVED) {
            throw new BusinessException(RejectReason.DIFF_ALREADY_RESOLVED,
                    "diff already resolved: " + diffKey);
        }
        ReconcileResolution resolution = new ReconcileResolution();
        resolution.setDiffKey(diffKey);
        resolution.setAction(request.action());
        resolution.setComment(request.comment());
        resolution.setOperator(request.operator() == null ? "system" : request.operator());
        resolution.setResolvedAt(Instant.now());
        reconcileRepository.saveResolution(resolution);

        diff.setStatus(DiffStatus.RESOLVED);
        reconcileRepository.saveDiff(diff);
        log.info("diff resolved key={} type={} action={} operator={}",
                diffKey, diff.getType(), request.action(), resolution.getOperator());
        return diff;
    }

    public List<ReconcileDiff> listDiffs(String channel, String reconDate, boolean openOnly) {
        return reconcileRepository.findDiffs(channel, reconDate).stream()
                .filter(ReconcileDiff::isActive)
                .filter(d -> !openOnly || d.getStatus() == DiffStatus.OPEN)
                .sorted(Comparator.comparing(ReconcileDiff::getDiffKey))
                .toList();
    }

    private List<ChannelFlow> parseCsv(List<String> csvLines, String expectedChannel) {
        List<ChannelFlow> flows = new ArrayList<>();
        boolean firstLine = true;
        for (String line : csvLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (firstLine && line.startsWith("flowId,")) {
                firstLine = false;
                continue;
            }
            firstLine = false;
            flows.add(parseFlow(line, expectedChannel));
        }
        return flows;
    }

    /** CSV 列：flowId,orderId,channel,merchantId,currency,amount,status,occurredAt(ISO) */
    private ChannelFlow parseFlow(String line, String expectedChannel) {
        String[] p = line.split(",", -1);
        if (p.length != 8) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "bad csv line (need 8 columns): " + line);
        }
        if (!expectedChannel.equals(p[2])) {
            throw new BusinessException(RejectReason.SOURCE_MISMATCH,
                    "csv channel " + p[2] + " != request channel " + expectedChannel);
        }
        ChannelFlow flow = new ChannelFlow();
        flow.setChannelFlowId(p[0]);
        flow.setOrderId(p[1]);
        flow.setChannel(p[2]);
        flow.setMerchantId(p[3]);
        flow.setCurrency(p[4]);
        try {
            flow.setAmount(Long.parseLong(p[5]));
        } catch (NumberFormatException e) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "bad amount: " + p[5]);
        }
        flow.setChannelStatus(p[6]);
        try {
            flow.setOccurredAt(Instant.parse(p[7]));
        } catch (DateTimeParseException e) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "bad timestamp: " + p[7]);
        }
        return flow;
    }

    private void validate(ReconcileRequest request) {
        if (request == null || request.channel() == null || request.channel().isBlank()
                || request.reconDate() == null || request.reconDate().isBlank()
                || request.csvLines() == null) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "channel, reconDate and csvLines are required");
        }
        try {
            LocalDate.parse(request.reconDate());
        } catch (DateTimeParseException e) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "reconDate must be yyyy-MM-dd");
        }
    }

    public static ReconcileDiffView toView(ReconcileDiff d) {
        return new ReconcileDiffView(d.getDiffKey(), d.getOrderId(), d.getChannelFlowId(),
                d.getType(), d.getStatus(), d.getLocalAmount(), d.getChannelAmount(),
                d.getLocalCurrency(), d.getChannelCurrency(),
                d.getLocalStatus(), d.getChannelStatus(), d.getSeenCount());
    }
}
