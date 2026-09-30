package com.github.xingjianluo.paymentledgerreconciliationjava.repo;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 通知幂等去重表：notificationId 全局唯一去重。 */
@Repository
public class NotificationDedupRepository {

    private enum State { PROCESSING, DONE }

    private final Map<String, State> processed = new ConcurrentHashMap<>();

    /**
     * 抢占处理权。
     * @return true 表示当前线程首次拿到该 notificationId 的处理权；false 表示已处理或处理中（并发/重复）。
     */
    public boolean markProcessing(String notificationId) {
        return processed.putIfAbsent(notificationId, State.PROCESSING) == null;
    }

    /** 处理成功：置为 DONE，此后任何重放都只能命中幂等。 */
    public void markDone(String notificationId) {
        processed.put(notificationId, State.DONE);
    }

    /** 处理失败：释放占位，允许通知在修正后重试（此时尚未产生任何业务效果）。 */
    public void clearProcessing(String notificationId) {
        processed.remove(notificationId, State.PROCESSING);
    }

    /** 测试隔离。 */
    public void reset() {
        processed.clear();
    }

    public boolean isProcessed(String notificationId) {
        return processed.get(notificationId) == State.DONE;
    }

    public Set<String> allProcessed() {
        return processed.entrySet().stream()
                .filter(e -> e.getValue() == State.DONE)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
