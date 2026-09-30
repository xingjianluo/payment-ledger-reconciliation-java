package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LedgerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 账本查询接口。 */
@RestController
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/api/ledger/accounts")
    public List<LedgerAccount> accounts() {
        return ledgerService.allAccounts();
    }

    @GetMapping("/api/ledger/entries")
    public List<LedgerEntry> entries(@RequestParam(required = false) String orderNo) {
        return orderNo == null ? ledgerService.allEntries() : ledgerService.entriesOfOrder(orderNo);
    }

    @GetMapping("/api/ledger/conservation")
    public Map<String, Object> conservation() {
        return Map.of("conserved", ledgerService.isConserved());
    }
}
