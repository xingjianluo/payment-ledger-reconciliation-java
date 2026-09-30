package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.LedgerEntry;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.AccountView;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.LedgerReport;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.AccountRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 账本查询：账户余额合计必须等于全部流水有符号金额合计（守恒） */
@Service
public class LedgerQueryService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerQueryService(AccountRepository accountRepository,
                              LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    public LedgerReport report() {
        List<AccountView> accounts = accountRepository.findAll().stream()
                .map(a -> new AccountView(a.getAccountId(), a.getCurrency(),
                        a.getBalance(), a.getVersion()))
                .toList();
        long sum = entryRepository.findAll().stream()
                .mapToLong(LedgerEntry::getSignedAmount)
                .sum();
        return new LedgerReport(sum, accounts);
    }
}
