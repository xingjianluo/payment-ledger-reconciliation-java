package com.github.xingjianluo.paymentledgerreconciliationjava.repo;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 对账批次内存仓储。 */
@Repository
public class ReconciliationRepository {

    private final ConcurrentHashMap<String, ReconciliationBatch> batches = new ConcurrentHashMap<>();
    private final AtomicLong detailIdSeq = new AtomicLong(0);
    private final Object repoLock = new Object();

    public ReconciliationBatch save(ReconciliationBatch batch) {
        batches.put(batch.getBatchId(), batch);
        return batch;
    }

    public Optional<ReconciliationBatch> findById(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }

    public List<ReconciliationBatch> findAll() {
        return batches.values().stream()
                .sorted(Comparator.comparingLong(ReconciliationBatch::getCreatedAt))
                .toList();
    }

    /** 测试隔离。 */
    public void reset() {
        synchronized (repoLock) {
            batches.clear();
            detailIdSeq.set(0);
        }
    }

    public long nextDetailId() {
        return detailIdSeq.incrementAndGet();
    }

    /** 批次级重入锁：同一批次的重跑串行化，避免并发重跑产生两份结论。 */
    public Object repoLock() {
        return repoLock;
    }
}
