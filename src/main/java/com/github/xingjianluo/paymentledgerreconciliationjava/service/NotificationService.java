package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException;
import com.github.xingjianluo.paymentledgerreconciliationjava.common.LockManager;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.LedgerEntryType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationOutcome;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.NotificationRecord;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.NotificationResult;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.PaymentNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.NotificationRecordRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 渠道通知处理（全部为本地模拟，无外部调用）。
 * 在订单级锁内完成：去重记录检查 -> 参数/来源/权限校验 -> 状态机校验 -> 账本入账 -> 更新订单，
 * 账本失败时订单状态不变，不产生部分落账。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final Map<NotificationType, Set<PaymentStatus>> ALLOWED_FROM =
            new EnumMap<>(NotificationType.class);
    private static final Map<NotificationType, PaymentStatus> TARGET =
            new EnumMap<>(NotificationType.class);
    private static final Map<NotificationType, LedgerEntryType> LEDGER =
            new EnumMap<>(NotificationType.class);

    static {
        ALLOWED_FROM.put(NotificationType.PAYMENT_SUCCESS, Set.of(PaymentStatus.CREATED));
        ALLOWED_FROM.put(NotificationType.PAYMENT_FAILED, Set.of(PaymentStatus.CREATED));
        ALLOWED_FROM.put(NotificationType.REFUND, Set.of(PaymentStatus.SUCCESS));
        ALLOWED_FROM.put(NotificationType.REVERSAL, Set.of(PaymentStatus.SUCCESS));
        TARGET.put(NotificationType.PAYMENT_SUCCESS, PaymentStatus.SUCCESS);
        TARGET.put(NotificationType.PAYMENT_FAILED, PaymentStatus.FAILED);
        TARGET.put(NotificationType.REFUND, PaymentStatus.REFUNDED);
        TARGET.put(NotificationType.REVERSAL, PaymentStatus.REVERSED);
        LEDGER.put(NotificationType.PAYMENT_SUCCESS, LedgerEntryType.PAYMENT_IN);
        LEDGER.put(NotificationType.REFUND, LedgerEntryType.REFUND_OUT);
        LEDGER.put(NotificationType.REVERSAL, LedgerEntryType.REVERSAL_OUT);
        LEDGER.put(NotificationType.PAYMENT_FAILED, null);
    }

    private final PaymentOrderRepository orderRepository;
    private final NotificationRecordRepository recordRepository;
    private final SignatureService signatureService;
    private final LedgerService ledgerService;
    private final LockManager lockManager;

    public NotificationService(PaymentOrderRepository orderRepository,
                               NotificationRecordRepository recordRepository,
                               SignatureService signatureService,
                               LedgerService ledgerService,
                               LockManager lockManager) {
        this.orderRepository = orderRepository;
        this.recordRepository = recordRepository;
        this.signatureService = signatureService;
        this.ledgerService = ledgerService;
        this.lockManager = lockManager;
    }

    public NotificationResult handle(PaymentNotification n) {
        validateShape(n);
        NotificationType type;
        try {
            type = NotificationType.valueOf(n.type());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "unknown notification type: " + n.type());
        }

        // 先按通知ID全局加锁（channelNotificationId 全局唯一），再按订单加锁，
        // 保证：同ID并发通知串行、跨订单复用同一ID必被拒绝；锁顺序固定为 通知 -> 订单 -> 账户
        final NotificationType notifType = type;
        final Object[] outcome = new Object[1];
        lockManager.withLock("notif:" + n.channelNotificationId(), () ->
                lockManager.withLock("order:" + n.orderId(), () ->
                        outcome[0] = doHandle(n, notifType)));
        return (NotificationResult) outcome[0];
    }

    private NotificationResult doHandle(PaymentNotification n, NotificationType type) {
        NotificationRecord existing =
                recordRepository.findByChannelNotificationId(n.channelNotificationId()).orElse(null);
        if (existing != null) {
            if (!existing.getOrderId().equals(n.orderId())
                    || !existing.getChannel().equals(n.channel())) {
                throw new BusinessException(RejectReason.DUPLICATE_NOTIFICATION,
                        "notificationId " + n.channelNotificationId()
                                + " already used for another order/channel");
            }
            PaymentOrder current = orderRepository.findById(n.orderId()).orElse(null);
            log.info("duplicate notification ignored id={} orderId={} priorOutcome={}",
                    n.channelNotificationId(), n.orderId(), existing.getOutcome());
            return new NotificationResult(n.orderId(),
                    current == null ? null : current.getStatus().name(),
                    NotificationOutcome.IDEMPOTENT, existing.getRejectReason(),
                    "duplicate notification: " + n.channelNotificationId());
        }

        PaymentOrder order = orderRepository.findById(n.orderId()).orElse(null);
        RejectReason rejection = validate(n, type, order);
        if (rejection != null) {
            // 订单尚不存在时不占用通知ID，订单稍后创建后仍可凭同一通知重试
            if (rejection != RejectReason.ORDER_NOT_FOUND) {
                saveRecord(n, type, NotificationOutcome.REJECTED, rejection);
            }
            log.warn("notification rejected id={} orderId={} type={} reason={}",
                    n.channelNotificationId(), n.orderId(), type, rejection);
            return new NotificationResult(n.orderId(),
                    order == null ? null : order.getStatus().name(),
                    NotificationOutcome.REJECTED, rejection, rejection.name());
        }

        LedgerEntryType ledgerType = LEDGER.get(type);
        if (ledgerType != null) {
            // 账本失败抛异常：订单不更新、通知不落 APPLIED 记录，无部分落账
            String key = n.type() + ":" + n.channelNotificationId();
            ledgerService.post(order.getOrderId(), order.getAccountId(), order.getCurrency(),
                    order.getAmount(), ledgerType, key);
        }

        order.setStatus(TARGET.get(type));
        order.setUpdatedAt(Instant.now());
        order.setVersion(order.getVersion() + 1);
        orderRepository.save(order);
        saveRecord(n, type, NotificationOutcome.APPLIED, null);
        log.info("notification applied id={} orderId={} type={} newStatus={}",
                n.channelNotificationId(), n.orderId(), type, order.getStatus());
        return new NotificationResult(order.getOrderId(), order.getStatus().name(),
                NotificationOutcome.APPLIED, null, "applied");
    }

    private RejectReason validate(PaymentNotification n, NotificationType type, PaymentOrder order) {
        if (order == null) {
            return RejectReason.ORDER_NOT_FOUND;
        }
        if (!signatureService.knowsChannel(n.channel())) {
            return RejectReason.SOURCE_MISMATCH;
        }
        String payload = String.join("|", n.channel(), n.channelNotificationId(), n.orderId(),
                String.valueOf(n.amount()), n.currency(), n.type());
        if (!signatureService.verify(n.channel(), payload, n.sign())) {
            return RejectReason.SIGNATURE_INVALID;
        }
        if (!order.getChannel().equals(n.channel())) {
            return RejectReason.SOURCE_MISMATCH;
        }
        if (!order.getMerchantId().equals(n.merchantId())) {
            return RejectReason.SOURCE_MISMATCH;
        }
        if (order.getAmount() != n.amount()) {
            return RejectReason.AMOUNT_MISMATCH;
        }
        if (!order.getCurrency().equals(n.currency())) {
            return RejectReason.CURRENCY_MISMATCH;
        }
        if (!ALLOWED_FROM.get(type).contains(order.getStatus())) {
            return RejectReason.INVALID_STATUS_TRANSITION;
        }
        return null;
    }

    private void validateShape(PaymentNotification n) {
        if (n == null
                || isBlank(n.channel())
                || isBlank(n.channelNotificationId())
                || isBlank(n.orderId())
                || isBlank(n.merchantId())
                || isBlank(n.type())
                || isBlank(n.currency())
                || isBlank(n.sign())
                || n.amount() == null) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "missing required fields");
        }
        if (n.amount() <= 0) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "amount must be positive");
        }
    }

    private void saveRecord(PaymentNotification n, NotificationType type,
                            NotificationOutcome outcome, RejectReason reason) {
        NotificationRecord record = new NotificationRecord();
        record.setChannelNotificationId(n.channelNotificationId());
        record.setOrderId(n.orderId());
        record.setChannel(n.channel());
        record.setType(type);
        record.setOutcome(outcome);
        record.setRejectReason(reason);
        record.setProcessedAt(Instant.now());
        recordRepository.save(record);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
