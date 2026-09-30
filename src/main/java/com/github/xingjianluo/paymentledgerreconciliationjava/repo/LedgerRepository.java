package com.github.xingjianluo.paymentledgerreconciliationjava.repo;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerDirection;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 账本内存仓储：账户余额 + 不可变分录，过账在单把锁内原子完成。 */
@Repository
public class LedgerRepository {

    private final ConcurrentHashMap<String, LedgerAccount> accounts = new ConcurrentHashMap<>();
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final AtomicLong entryIdSeq = new AtomicLong(0);
    private final Object ledgerLock = new Object();

    public LedgerAccount getOrCreateAccount(String accountId, String merchantId, String currency) {
        return accounts.computeIfAbsent(accountId,
                k -> new LedgerAccount(accountId, merchantId, currency));
    }

    /**
     * 原子过账：
     * 1) 校验整批分录按币种借贷平衡；
     * 2) 锁内先把所有新余额计算到临时表（不触碰存量状态）；
     * 3) 全部就绪后一次性提交余额并追加分录。
     * 任何一步异常都不会留下半条分录或余额漂移。
     */
    public List<LedgerEntry> postEntries(List<LedgerEntry> incoming) {
        // 先在锁外做不可变校验
        validateBalanced(incoming);
        synchronized (ledgerLock) {
            // 第一步：在临时增量表里汇总每个账户的净变化，不触碰存量余额
            Map<String, Long> deltaByAccount = new HashMap<>();
            for (LedgerEntry e : incoming) {
                // 确保账户已建（仅构建对象，余额尚未变动）
                getOrCreateAccount(e.accountId(), merchantPartOf(e.accountId()), e.currency());
                long signed = e.direction() == LedgerDirection.DEBIT ? -e.amount() : e.amount();
                deltaByAccount.merge(e.accountId(), signed, Long::sum);
            }
            // 第二步：全部计算完毕后一次性提交余额，再追加分录（同一把锁，无中间态外泄）
            deltaByAccount.forEach((id, delta) -> {
                LedgerAccount account = accounts.get(id);
                account.setBalance(account.getBalance() + delta);
                account.setVersion(account.getVersion() + 1);
            });
            entries.addAll(incoming);
            return List.copyOf(incoming);
        }
    }

    private void validateBalanced(List<LedgerEntry> incoming) {
        Map<String, Long> deltaByCurrency = new HashMap<>();
        for (LedgerEntry e : incoming) {
            if (e.amount() <= 0) {
                throw new IllegalArgumentException("分录金额必须为正数: " + e.accountId());
            }
            long signed = e.direction() == LedgerDirection.DEBIT ? -e.amount() : e.amount();
            deltaByCurrency.merge(e.currency(), signed, Long::sum);
        }
        deltaByCurrency.forEach((ccy, sum) -> {
            if (sum != 0) {
                throw new IllegalStateException("借贷不平衡，币种 " + ccy + " 净额=" + sum);
            }
        });
    }

    private String merchantPartOf(String accountId) {
        if (accountId.startsWith("MERCHANT:")) {
            // MERCHANT:{merchantId}:{currency}
            String[] parts = accountId.split(":", 3);
            return parts.length >= 2 ? parts[1] : null;
        }
        return null;
    }

    public List<LedgerEntry> findEntriesByOrder(String orderNo) {
        synchronized (ledgerLock) {
            return entries.stream().filter(e -> e.orderNo().equals(orderNo)).toList();
        }
    }

    public List<LedgerEntry> findAllEntries() {
        synchronized (ledgerLock) {
            return entries.stream()
                    .sorted(Comparator.comparingLong(LedgerEntry::id))
                    .toList();
        }
    }

    public List<LedgerAccount> findAllAccounts() {
        return accounts.values().stream()
                .sorted(Comparator.comparing(LedgerAccount::getAccountId))
                .toList();
    }

    /** 测试隔离：清空账户、分录与序号。 */
    public void reset() {
        synchronized (ledgerLock) {
            accounts.clear();
            entries.clear();
            entryIdSeq.set(0);
        }
    }

    public long nextEntryId() {
        return entryIdSeq.incrementAndGet();
    }

    public Object ledgerLock() {
        return ledgerLock;
    }
}
