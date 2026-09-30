package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.dto.LedgerReport;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LedgerQueryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerQueryService ledgerQueryService;

    public LedgerController(LedgerQueryService ledgerQueryService) {
        this.ledgerQueryService = ledgerQueryService;
    }

    @GetMapping("/report")
    public LedgerReport report() {
        return ledgerQueryService.report();
    }
}
