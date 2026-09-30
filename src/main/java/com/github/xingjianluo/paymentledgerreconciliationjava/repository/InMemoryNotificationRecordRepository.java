package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.NotificationRecord;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存通知记录仓储，channelNotificationId 全局唯一 */
@Repository
public class InMemoryNotificationRecordRepository implements NotificationRecordRepository {

    private final ConcurrentHashMap<String, NotificationRecord> store = new ConcurrentHashMap<>();

    @Override
    public NotificationRecord save(NotificationRecord record) {
        store.put(record.getChannelNotificationId(), record);
        return record;
    }

    @Override
    public Optional<NotificationRecord> findByChannelNotificationId(String channelNotificationId) {
        return Optional.ofNullable(store.get(channelNotificationId));
    }

    @Override
    public List<NotificationRecord> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(NotificationRecord::getProcessedAt)
                        .thenComparing(NotificationRecord::getChannelNotificationId))
                .toList();
    }
}
