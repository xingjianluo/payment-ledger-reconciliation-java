package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.NotificationRecord;

import java.util.List;
import java.util.Optional;

public interface NotificationRecordRepository {
    NotificationRecord save(NotificationRecord record);
    Optional<NotificationRecord> findByChannelNotificationId(String channelNotificationId);
    List<NotificationRecord> findAll();
}
