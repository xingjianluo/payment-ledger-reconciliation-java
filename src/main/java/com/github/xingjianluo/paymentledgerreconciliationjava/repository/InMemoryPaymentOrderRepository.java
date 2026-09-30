package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存支付单仓储 */
@Repository
public class InMemoryPaymentOrderRepository implements PaymentOrderRepository {

    private final ConcurrentHashMap<String, PaymentOrder> store = new ConcurrentHashMap<>();

    @Override
    public PaymentOrder save(PaymentOrder order) {
        store.put(order.getOrderId(), order);
        return order;
    }

    @Override
    public Optional<PaymentOrder> findById(String orderId) {
        return Optional.ofNullable(store.get(orderId));
    }

    @Override
    public List<PaymentOrder> findByChannel(String channel) {
        return store.values().stream()
                .filter(o -> channel == null || channel.equals(o.getChannel()))
                .sorted(Comparator.comparing(PaymentOrder::getOrderId))
                .toList();
    }
}
