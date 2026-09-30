package com.github.xingjianluo.paymentledgerreconciliationjava.dto;

public record ResolveDiffRequest(
        String action,
        String comment,
        String operator
) { }
