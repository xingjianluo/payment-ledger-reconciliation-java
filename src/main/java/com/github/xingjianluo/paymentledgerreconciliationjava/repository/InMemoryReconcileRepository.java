package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileBatch;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileDiff;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileResolution;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存对账仓储：批次只追加，差异按稳定键 upsert，处理结论只追加 */
@Repository
public class InMemoryReconcileRepository implements ReconcileRepository {

    private final ConcurrentHashMap<String, ReconcileBatch> batches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReconcileDiff> diffs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReconcileResolution> resolutions = new ConcurrentHashMap<>();

    @Override
    public ReconcileBatch saveBatch(ReconcileBatch batch) {
        batches.put(batch.getBatchId(), batch);
        return batch;
    }

    @Override
    public List<ReconcileBatch> findBatches(String channel, String reconDate) {
        return batches.values().stream()
                .filter(b -> channel == null || channel.equals(b.getChannel()))
                .filter(b -> reconDate == null || reconDate.equals(b.getReconDate()))
                .sorted(Comparator.comparing(ReconcileBatch::getRanAt))
                .toList();
    }

    @Override
    public ReconcileDiff saveDiff(ReconcileDiff diff) {
        diffs.put(diff.getDiffKey(), diff);
        return diff;
    }

    @Override
    public Optional<ReconcileDiff> findDiffByKey(String diffKey) {
        return Optional.ofNullable(diffs.get(diffKey));
    }

    @Override
    public List<ReconcileDiff> findDiffs(String channel, String reconDate) {
        return diffs.values().stream()
                .filter(d -> channel == null || channel.equals(d.getChannel()))
                .filter(d -> reconDate == null || reconDate.equals(d.getReconDate()))
                .sorted(Comparator.comparing(ReconcileDiff::getDiffKey))
                .toList();
    }

    @Override
    public ReconcileResolution saveResolution(ReconcileResolution resolution) {
        resolutions.put(resolution.getDiffKey(), resolution);
        return resolution;
    }

    @Override
    public Optional<ReconcileResolution> findResolution(String diffKey) {
        return Optional.ofNullable(resolutions.get(diffKey));
    }

    @Override
    public List<ReconcileResolution> findAllResolutions() {
        return resolutions.values().stream()
                .sorted(Comparator.comparing(ReconcileResolution::getResolvedAt))
                .toList();
    }
}
