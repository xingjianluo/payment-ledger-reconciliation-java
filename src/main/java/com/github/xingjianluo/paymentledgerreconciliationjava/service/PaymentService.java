package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.PaymentOrderRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.support.PaymentBusinessException;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 支付单创建与查询，以及支付状态机。 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** 合法状态迁移表。终态 SUCCESS 只能走向 REFUNDED / REVERSED；FAILED 为终态；退款/冲正后不可再变。 */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(PaymentStatus.CREATED,
                EnumSet.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED));
        ALLOWED.put(PaymentStatus.SUCCESS,
                EnumSet.of(PaymentStatus.REFUNDED, PaymentStatus.REVERSED));
        ALLOWED.put(PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(PaymentStatus.REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(PaymentStatus.REVERSED, EnumSet.noneOf(PaymentStatus.class));
    }

    private final PaymentOrderRepository orderRepository;
    private final MerchantDirectory merchantDirectory;

    public PaymentService(PaymentOrderRepository orderRepository, MerchantDirectory merchantDirectory) {
        this.orderRepository = orderRepository;
        this.merchantDirectory = merchantDirectory;
    }

    public PaymentOrder createOrder(String orderNo, String merchantId, String channel,
                                    long amount, String currency, String apiKey) {
        if (merchantId == null || !merchantDirectory.merchantExists(merchantId)) {
            throw new PaymentBusinessException(RejectReason.MERCHANT_MISMATCH,
                    "未知商户: " + merchantId);
        }
        if (!merchantDirectory.supportsChannel(merchantId, channel)) {
            throw new PaymentBusinessException(RejectReason.SOURCE_MISMATCH,
                    "商户 " + merchantId + " 未开通渠道 " + channel);
        }
        if (!merchantDirectory.isValidApiKey(merchantId, apiKey)) {
            throw new PaymentBusinessException(RejectReason.PERMISSION_DENIED,
                    "API Key 无效，无权为商户 " + merchantId + " 建单");
        }
        if (amount <= 0) {
            throw new PaymentBusinessException(RejectReason.AMOUNT_MISMATCH,
                    "支付金额必须为正数: " + amount);
        }
        if (currency == null || currency.isBlank()) {
            throw new PaymentBusinessException(RejectReason.CURRENCY_MISMATCH, "币种不能为空");
        }
        String no = orderNo == null || orderNo.isBlank()
                ? "ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : orderNo;
        if (orderRepository.findById(no).isPresent()) {
            throw new PaymentBusinessException(RejectReason.DUPLICATE_NOTIFICATION,
                    "支付单号已存在: " + no);
        }
        long now = System.currentTimeMillis();
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(no);
        order.setMerchantId(merchantId);
        order.setChannel(channel);
        order.setAmount(amount);
        order.setCurrency(currency);
        order.setStatus(PaymentStatus.CREATED);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        log.info("ORDER CREATED orderNo={} merchant={} channel={} amount={} {}",
                no, merchantId, channel, amount, currency);
        return order;
    }

    public PaymentOrder getOrder(String orderNo) {
        return orderRepository.findById(orderNo)
                .orElseThrow(() -> new PaymentBusinessException(RejectReason.ORDER_NOT_FOUND,
                        "支付单不存在: " + orderNo));
    }

    /**
     * 在调用方持有的单号锁内执行状态迁移（供 NotificationService 使用）。
     */
    public PaymentStatus targetStatusOf(NotificationType type) {
        return switch (type) {
            case SUCCESS -> PaymentStatus.SUCCESS;
            case FAILURE -> PaymentStatus.FAILED;
            case REFUND -> PaymentStatus.REFUNDED;
            case REVERSAL -> PaymentStatus.REVERSED;
        };
    }

    /** 校验状态迁移是否合法，不改动单据。非法跳转抛 ILLEGAL_TRANSITION。 */
    public void validateTransition(PaymentOrder order, NotificationType type) {
        PaymentStatus from = order.getStatus();
        PaymentStatus to = targetStatusOf(type);
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new PaymentBusinessException(RejectReason.ILLEGAL_TRANSITION,
                    "非法状态跳转: orderNo=" + order.getOrderNo() + " " + from + "->" + to);
        }
    }

    /** 校验并落库状态迁移。调用前必须已通过 validateTransition。 */
    public void transition(ChannelNotification notification, PaymentOrder order) {
        PaymentStatus from = order.getStatus();
        PaymentStatus to = targetStatusOf(notification.getType());
        order.setStatus(to);
        order.setLastNotificationId(notification.getNotificationId());
        order.setUpdatedAt(System.currentTimeMillis());
        orderRepository.save(order);
        log.info("ORDER TRANSITION orderNo={} {} -> {} by notificationId={}",
                order.getOrderNo(), from, to, notification.getNotificationId());
    }

    /** applyNotification 保留为入口占位；真实联动由 NotificationService 在去重与锁内完成。 */
    public void applyNotification(ChannelNotification notification) {
        throw new UnsupportedOperationException("请使用 NotificationService.handle");
    }
}
