package com.github.xingjianluo.paymentledgerreconciliationjava.common;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按业务键提供互斥锁。
 * 通知处理对同一订单加锁，账本入账对同一账户加锁，保证：
 * 1) 同一订单的并发/重复通知串行化，去重记录只可能插入一次；
 * 2) 同一账户的余额变动串行化，读余额-写余额为原子操作。
 */
@Component
public class LockManager {

    private static final int STRIPES = 64;
    private final Object[] stripes = new Object[STRIPES];

    public LockManager() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new Object();
        }
    }

    private Object lockFor(String key) {
        return stripes[Math.floorMod(key.hashCode(), STRIPES)];
    }

    public void withLock(String key, Runnable action) {
        synchronized (lockFor(key)) {
            action.run();
        }
    }
}
