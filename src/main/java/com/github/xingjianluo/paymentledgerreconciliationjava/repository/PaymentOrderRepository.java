package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;

import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository {
    PaymentOrder save(PaymentOrder order);
    Optional<PaymentOrder> findById(String orderId);
    List<PaymentOrder> findByChannel(String channel);
}
