package com.github.xingjianluo.paymentledgerreconciliationjava.repo;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentOrder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 支付单内存仓储（按单号分段加锁，不依赖外部数据库）。 */
@Repository
public class PaymentOrderRepository {

    private final ConcurrentHashMap<String, PaymentOrder> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public PaymentOrder save(PaymentOrder order) {
        orders.put(order.getOrderNo(), order);
        return order;
    }

    public Optional<PaymentOrder> findById(String orderNo) {
        return Optional.ofNullable(orders.get(orderNo));
    }

    public List<PaymentOrder> findAll() {
        return orders.values().stream().toList();
    }

    public List<PaymentOrder> findByChannel(String channel) {
        return orders.values().stream()
                .filter(o -> channel.equals(o.getChannel()))
                .toList();
    }

    /** 测试隔离：清空全部单据。 */
    public void reset() {
        orders.clear();
    }

    /** 返回该单号专用的锁对象；同一单号的并发通知串行化，不同单号互不阻塞。 */
    public Object lockFor(String orderNo) {
        return locks.computeIfAbsent(orderNo, k -> new Object());
    }
}
