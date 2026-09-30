package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存账本流水仓储，流水只追加不修改 */
@Repository
public class InMemoryLedgerEntryRepository implements LedgerEntryRepository {

    private final ConcurrentHashMap<String, LedgerEntry> byEntryId = new ConcurrentHashMap<>();

    @Override
    public LedgerEntry save(LedgerEntry entry) {
        byEntryId.put(entry.getEntryId(), entry);
        return entry;
    }

    @Override
    public Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
        return byEntryId.values().stream()
                .filter(e -> idempotencyKey.equals(e.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public List<LedgerEntry> findByOrderId(String orderId) {
        return byEntryId.values().stream()
                .filter(e -> orderId.equals(e.getOrderId()))
                .sorted(Comparator.comparing(LedgerEntry::getCreatedAt)
                        .thenComparing(LedgerEntry::getEntryId))
                .toList();
    }

    @Override
    public List<LedgerEntry> findAll() {
        return byEntryId.values().stream()
                .sorted(Comparator.comparing(LedgerEntry::getCreatedAt)
                        .thenComparing(LedgerEntry::getEntryId))
                .toList();
    }
}
