package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.NotificationDedupRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.PaymentOrderRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.support.PaymentBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 回调通知处理：来源 / 权限 / 金额 / 币种 / 状态机校验，去重与账本原子联动。 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationDedupRepository dedupRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentService paymentService;
    private final LedgerService ledgerService;
    private final MerchantDirectory merchantDirectory;
    private final SignatureVerifier signatureVerifier;

    public NotificationService(NotificationDedupRepository dedupRepository,
                               PaymentOrderRepository orderRepository,
                               PaymentService paymentService,
                               LedgerService ledgerService,
                               MerchantDirectory merchantDirectory,
                               SignatureVerifier signatureVerifier) {
        this.dedupRepository = dedupRepository;
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.ledgerService = ledgerService;
        this.merchantDirectory = merchantDirectory;
        this.signatureVerifier = signatureVerifier;
    }

    /**
     * 处理一条渠道通知，返回可区分结果。重复通知幂等命中（duplicate=true, accepted=true）；
     * 非法通知被拒绝（accepted=false），reason 为具体原因码。
     *
     * @param apiKey 回调请求方携带的 API Key，用于商户权限校验
     */
    public NotificationResult handle(ChannelNotification n, String apiKey) {
        String nid = n.getNotificationId();

        // 0) 权限：调用方必须持有通知商户的有效 API Key（来源权限匹配）
        if (n.getMerchantId() == null || !merchantDirectory.isValidApiKey(n.getMerchantId(), apiKey)) {
            log.warn("NOTIFY REJECTED notificationId={} orderNo={} reason={}",
                    nid, n.getOrderNo(), RejectReason.PERMISSION_DENIED);
            return new NotificationResult(false, false,
                    RejectReason.PERMISSION_DENIED.name(), "API Key 缺失或无权操作该商户回调");
        }

        // 1) 全局去重：已完成的通知只生效一次，重放直接幂等返回
        if (dedupRepository.isProcessed(nid)) {
            log.info("NOTIFY DUPLICATE notificationId={} orderNo={} 已处理过，幂等跳过", nid, n.getOrderNo());
            return new NotificationResult(true, true,
                    RejectReason.DUPLICATE_NOTIFICATION.name(), "重复通知，仅生效一次");
        }

        // 2) 抢占处理权：并发的同一通知只有一个线程能进入
        if (!dedupRepository.markProcessing(nid)) {
            log.info("NOTIFY CONCURRENT notificationId={} orderNo={} 已有处理在途，拒绝并发重复执行",
                    nid, n.getOrderNo());
            return new NotificationResult(false, true,
                    RejectReason.DUPLICATE_NOTIFICATION.name(), "通知正在处理中，并发重复被拒绝");
        }

        try {
            // 3) 基础校验 + 业务处理；任何拒绝都在产生业务效果之前
            process(n);
        } catch (PaymentBusinessException ex) {
            // 尚未产生业务效果（状态与账本均未改），释放占位允许修正后重试
            dedupRepository.clearProcessing(nid);
            log.warn("NOTIFY REJECTED notificationId={} orderNo={} reason={} detail={}",
                    nid, n.getOrderNo(), ex.getReason(), ex.getMessage());
            return new NotificationResult(false, false, ex.getReason().name(), ex.getMessage());
        } catch (RuntimeException ex) {
            dedupRepository.clearProcessing(nid);
            log.error("NOTIFY ERROR notificationId={} orderNo={} 错误={}", nid, n.getOrderNo(), ex.toString());
            return new NotificationResult(false, false,
                    RejectReason.LEDGER_POSTING_FAILED.name(), ex.getMessage());
        }

        // 4) 成功落地：置 DONE，之后任何重放都只能幂等
        dedupRepository.markDone(nid);
        log.info("NOTIFY ACCEPTED notificationId={} orderNo={} type={}",
                nid, n.getOrderNo(), n.getType());
        return new NotificationResult(true, false, null, "通知处理成功");
    }

    /** 完整校验链；按顺序抛出最早的、可区分的拒绝原因。 */
    private void process(ChannelNotification n) {
        PaymentOrder order = orderRepository.findById(n.getOrderNo())
                .orElseThrow(() -> new PaymentBusinessException(RejectReason.ORDER_NOT_FOUND,
                        "支付单不存在: " + n.getOrderNo()));

        if (!order.getMerchantId().equals(n.getMerchantId())) {
            throw new PaymentBusinessException(RejectReason.MERCHANT_MISMATCH,
                    "通知商户与支付单不一致: order=" + order.getMerchantId() + " notify=" + n.getMerchantId());
        }
        if (!order.getChannel().equals(n.getChannel())
                || !merchantDirectory.supportsChannel(n.getMerchantId(), n.getChannel())) {
            throw new PaymentBusinessException(RejectReason.SOURCE_MISMATCH,
                    "来源渠道不匹配或未授权: order=" + order.getChannel() + " notify=" + n.getChannel());
        }
        // 金额/币种先于签名判定：业务字段不一致必须能与“伪造签名”精确区分
        if (order.getAmount() != n.getAmount()) {
            throw new PaymentBusinessException(RejectReason.AMOUNT_MISMATCH,
                    "金额不一致: order=" + order.getAmount() + " notify=" + n.getAmount());
        }
        if (!order.getCurrency().equals(n.getCurrency())) {
            throw new PaymentBusinessException(RejectReason.CURRENCY_MISMATCH,
                    "币种不一致: order=" + order.getCurrency() + " notify=" + n.getCurrency());
        }
        String secret = merchantDirectory.secretOf(n.getMerchantId(), n.getChannel());
        String payload = signaturePayload(n);
        if (!signatureVerifier.verify(payload, secret, n.getSignature())) {
            throw new PaymentBusinessException(RejectReason.SIGNATURE_INVALID,
                    "渠道签名校验失败: notificationId=" + n.getNotificationId());
        }

        // 同一把单号锁内完成“状态校验 + 账本过账 + 状态落库”，并发的不同通知被串行化
        synchronized (orderRepository.lockFor(order.getOrderNo())) {
            // 锁内重读，避免拿到进入锁之前的陈旧状态
            PaymentOrder latest = orderRepository.findById(order.getOrderNo()).orElseThrow();
            // 先校验状态机：非法跳转在任何资金动作之前拒绝，不留分录
            paymentService.validateTransition(latest, n.getType());
            // 再原子过账：过账失败会抛异常，状态不迁移（postEntries 内部全有或全无）
            ledgerService.post(latest.getOrderNo(), latest.getMerchantId(), latest.getChannel(),
                    latest.getCurrency(), latest.getAmount(), n.getType(), n.getNotificationId());
            paymentService.transition(n, latest);
        }
    }

    /** 与模拟器一致的签名原文：核心业务字段拼接。 */
    public static String signaturePayload(ChannelNotification n) {
        return String.join("|",
                n.getNotificationId(),
                n.getOrderNo(),
                String.valueOf(n.getMerchantId()),
                String.valueOf(n.getChannel()),
                n.getType().name(),
                String.valueOf(n.getAmount()),
                String.valueOf(n.getCurrency()));
    }

    /** 处理结果。 */
    public record NotificationResult(boolean accepted, boolean duplicate, String reason, String message) {
    }
}
