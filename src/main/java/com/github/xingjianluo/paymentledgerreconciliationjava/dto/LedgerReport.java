package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

import java.util.List;

public record LedgerReport(long sumSignedEntries, List<AccountView> accounts) { }
