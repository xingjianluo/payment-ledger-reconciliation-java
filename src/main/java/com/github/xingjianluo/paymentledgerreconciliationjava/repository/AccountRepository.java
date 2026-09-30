package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.Account;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {
    Account save(Account account);
    Optional<Account> findById(String accountId);
    List<Account> findAll();
}
