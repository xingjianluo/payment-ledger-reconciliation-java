package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.PaymentStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.Account;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.CreateOrderRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.AccountRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 支付单创建与查询 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final SignatureService signatureService;

    public PaymentService(PaymentOrderRepository orderRepository,
                          AccountRepository accountRepository,
                          SignatureService signatureService) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.signatureService = signatureService;
    }

    public PaymentOrder createOrder(CreateOrderRequest request) {
        validate(request);
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new BusinessException(RejectReason.INVALID_REQUEST,
                        "account not found: " + request.accountId()));
        if (!account.getCurrency().equals(request.currency())) {
            throw new BusinessException(RejectReason.CURRENCY_MISMATCH,
                    "account currency " + account.getCurrency()
                            + " != order currency " + request.currency());
        }

        Instant now = Instant.now();
        PaymentOrder order = new PaymentOrder();
        order.setOrderId("ORD-" + UUID.randomUUID());
        order.setMerchantId(request.merchantId());
        order.setAccountId(request.accountId());
        order.setChannel(request.channel());
        order.setCurrency(request.currency());
        order.setAmount(request.amount());
        order.setStatus(PaymentStatus.CREATED);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setVersion(0);
        orderRepository.save(order);
        log.info("order created orderId={} merchant={} account={} channel={} currency={} amount={}",
                order.getOrderId(), order.getMerchantId(), order.getAccountId(),
                order.getChannel(), order.getCurrency(), order.getAmount());
        return order;
    }

    public PaymentOrder getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(RejectReason.ORDER_NOT_FOUND,
                        "order not found: " + orderId));
    }

    private void validate(CreateOrderRequest request) {
        if (request == null
                || isBlank(request.merchantId())
                || isBlank(request.accountId())
                || isBlank(request.channel())
                || isBlank(request.currency())
                || request.amount() == null) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "missing required fields");
        }
        if (request.amount() <= 0) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "amount must be positive");
        }
        if (!signatureService.knowsChannel(request.channel())) {
            throw new BusinessException(RejectReason.SOURCE_MISMATCH,
                    "unknown channel: " + request.channel());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
