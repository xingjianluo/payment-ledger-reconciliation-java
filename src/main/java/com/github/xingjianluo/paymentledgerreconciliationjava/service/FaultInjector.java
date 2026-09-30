package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试用故障注入：可在下一次过账时抛出异常，验证“半落账必须回滚、余额不漂移”。
 * 仅用于本地测试，不影响正常流程。
 */
@Component
public class FaultInjector {

    private final AtomicBoolean failNext = new AtomicBoolean(false);

    /** 安排下一次过账失败（一次性）。 */
    public void failNextPost() {
        failNext.set(true);
    }

    public boolean shouldFail() {
        // 消费式读取：只失败一次，模拟过账中途异常
        return failNext.compareAndSet(true, false);
    }

    public void reset() {
        failNext.set(false);
    }
}
