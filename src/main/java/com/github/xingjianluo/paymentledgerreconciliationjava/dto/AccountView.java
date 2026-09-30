package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

public record AccountView(String accountId, String currency, long balance, long version) { }
