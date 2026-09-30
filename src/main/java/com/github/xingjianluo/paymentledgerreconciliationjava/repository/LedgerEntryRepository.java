package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.LedgerEntry;

import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository {
    LedgerEntry save(LedgerEntry entry);
    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);
    List<LedgerEntry> findByOrderId(String orderId);
    List<LedgerEntry> findAll();
}
