package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.Account;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存账户仓储 */
@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentHashMap<String, Account> store = new ConcurrentHashMap<>();

    @Override
    public Account save(Account account) {
        store.put(account.getAccountId(), account);
        return account;
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return Optional.ofNullable(store.get(accountId));
    }

    @Override
    public List<Account> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(Account::getAccountId))
                .toList();
    }
}
