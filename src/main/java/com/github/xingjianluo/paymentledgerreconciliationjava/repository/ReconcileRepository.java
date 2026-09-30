package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileBatch;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileDiff;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileResolution;

import java.util.List;
import java.util.Optional;

public interface ReconcileRepository {
    ReconcileBatch saveBatch(ReconcileBatch batch);
    List<ReconcileBatch> findBatches(String channel, String reconDate);
    ReconcileDiff saveDiff(ReconcileDiff diff);
    Optional<ReconcileDiff> findDiffByKey(String diffKey);
    List<ReconcileDiff> findDiffs(String channel, String reconDate);
    ReconcileResolution saveResolution(ReconcileResolution resolution);
    Optional<ReconcileResolution> findResolution(String diffKey);
    List<ReconcileResolution> findAllResolutions();
}
