package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.common.BusinessException;
import com.github.xingjianluo.paymentledgerreconciliationjava.common.LockManager;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.LedgerEntryType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.Account;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.LedgerEntry;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.AccountRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 账本入账：在账户级锁内完成 “幂等检查 -> 余额校验 -> 写流水 -> 更新余额”，
 * 全部成功才生效，任何一步抛出异常都不会留下流水或余额漂移。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LockManager lockManager;

    /** 测试用故障注入：对下一次匹配 orderId 的入账抛出异常 */
    private final AtomicReference<String> failNextForOrder = new AtomicReference<>();

    public LedgerService(AccountRepository accountRepository,
                         LedgerEntryRepository entryRepository,
                         LockManager lockManager) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.lockManager = lockManager;
    }

    public void injectFailureForNextPost(String orderId) {
        failNextForOrder.set(orderId);
    }

    /**
     * @param amount         正数金额（最小币种单位）
     * @param type           PAYMENT_IN 记正；REFUND_OUT / REVERSAL_OUT 记负
     * @param idempotencyKey 幂等键，重复提交只生效一次
     */
    public void post(String orderId, String accountId, String currency, long amount,
                     LedgerEntryType type, String idempotencyKey) {
        if (amount <= 0) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "ledger amount must be positive: " + amount);
        }
        lockManager.withLock("account:" + accountId, () ->
                doPost(orderId, accountId, currency, amount, type, idempotencyKey));
    }

    private void doPost(String orderId, String accountId, String currency, long amount,
                        LedgerEntryType type, String idempotencyKey) {
        if (entryRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            log.info("ledger idempotent hit key={} orderId={}", idempotencyKey, orderId);
            return;
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(RejectReason.LEDGER_POST_FAILED,
                        "account not found: " + accountId));
        if (!account.getCurrency().equals(currency)) {
            throw new BusinessException(RejectReason.CURRENCY_MISMATCH,
                    "ledger currency mismatch account=" + account.getCurrency()
                            + " entry=" + currency);
        }

        long signed = switch (type) {
            case PAYMENT_IN -> amount;
            case REFUND_OUT, REVERSAL_OUT -> -amount;
        };
        long newBalance = account.getBalance() + signed;
        if (newBalance < 0) {
            throw new BusinessException(RejectReason.LEDGER_POST_FAILED,
                    "insufficient balance account=" + accountId
                            + " balance=" + account.getBalance() + " debit=" + amount);
        }

        if (orderId.equals(failNextForOrder.get())) {
            failNextForOrder.compareAndSet(orderId, null);
            throw new BusinessException(RejectReason.LEDGER_POST_FAILED,
                    "injected ledger failure for order " + orderId);
        }

        LedgerEntry entry = new LedgerEntry();
        entry.setEntryId("ENT-" + UUID.randomUUID());
        entry.setOrderId(orderId);
        entry.setAccountId(accountId);
        entry.setCurrency(currency);
        entry.setType(type);
        entry.setSignedAmount(signed);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setCreatedAt(Instant.now());
        entryRepository.save(entry);

        account.setBalance(newBalance);
        account.setVersion(account.getVersion() + 1);
        accountRepository.save(account);
        log.info("ledger posted orderId={} account={} type={} signed={} balance={} version={} key={}",
                orderId, accountId, type, signed, newBalance, account.getVersion(), idempotencyKey);
    }
}
