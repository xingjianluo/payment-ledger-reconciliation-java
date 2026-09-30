package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerDirection;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 账本服务：入账 / 退款 / 冲正均为一组成对分录的原子过账，失败整体回滚。 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository ledgerRepository;
    private final FaultInjector faultInjector;

    public LedgerService(LedgerRepository ledgerRepository, FaultInjector faultInjector) {
        this.ledgerRepository = ledgerRepository;
        this.faultInjector = faultInjector;
    }

    /**
     * 按通知类型原子过账：
     * SUCCESS  借：渠道清算  贷：商户应收
     * REFUND / REVERSAL  借：商户应收  贷：渠道清算（方向相反的红冲，金额相等）
     * FAILURE 不产生分录（支付未成功，无资金移动）。
     */
    public List<LedgerEntry> post(String orderNo, String merchantId, String channel,
                                  String currency, long amount, NotificationType type,
                                  String notificationId) {
        if (type == NotificationType.FAILURE) {
            log.info("LEDGER skip orderNo={} notificationId={} type=FAILURE 无资金移动", orderNo, notificationId);
            return List.of();
        }
        String merchantAccount = "MERCHANT:" + merchantId + ":" + currency;
        String clearingAccount = "CLEARING:" + channel + ":" + currency;
        long now = System.currentTimeMillis();

        // 在锁外先把不可变分录构造齐全；金额相等、方向相反，天然借贷平衡
        List<LedgerEntry> batch = new ArrayList<>(2);
        if (type == NotificationType.SUCCESS) {
            long id1 = ledgerRepository.nextEntryId();
            long id2 = ledgerRepository.nextEntryId();
            batch.add(new LedgerEntry(id1, orderNo, notificationId, clearingAccount,
                    LedgerDirection.DEBIT, amount, currency, "支付入账-渠道清算", now));
            batch.add(new LedgerEntry(id2, orderNo, notificationId, merchantAccount,
                    LedgerDirection.CREDIT, amount, currency, "支付入账-商户应收", now));
        } else {
            String memo = type == NotificationType.REFUND ? "退款红冲" : "冲正红冲";
            long id1 = ledgerRepository.nextEntryId();
            long id2 = ledgerRepository.nextEntryId();
            batch.add(new LedgerEntry(id1, orderNo, notificationId, merchantAccount,
                    LedgerDirection.DEBIT, amount, currency, memo + "-商户应收", now));
            batch.add(new LedgerEntry(id2, orderNo, notificationId, clearingAccount,
                    LedgerDirection.CREDIT, amount, currency, memo + "-渠道清算", now));
        }

        // 故障注入：模拟过账中途异常。此时尚未写入任何余额/分录，账本保持原状
        if (faultInjector.shouldFail()) {
            log.error("LEDGER FAULT orderNo={} notificationId={} type={} 模拟过账异常，整批放弃",
                    orderNo, notificationId, type);
            throw new IllegalStateException("模拟过账中途失败: orderNo=" + orderNo);
        }

        try {
            List<LedgerEntry> posted = ledgerRepository.postEntries(batch);
            log.info("LEDGER POSTED orderNo={} notificationId={} type={} amount={} {} entries={} 账户余额已更新",
                    orderNo, notificationId, type, amount, currency,
                    posted.stream().map(LedgerEntry::id).toList());
            return posted;
        } catch (RuntimeException ex) {
            // postEntries 内部要么全部成功要么完全不动；这里只记录，不补偿、不留半截账
            log.error("LEDGER POST FAILED orderNo={} notificationId={} type={} 原因={} 账本无改动",
                    orderNo, notificationId, type, ex.getMessage());
            throw ex;
        }
    }

    public List<LedgerEntry> entriesOfOrder(String orderNo) {
        return ledgerRepository.findEntriesByOrder(orderNo);
    }

    public List<LedgerAccount> allAccounts() {
        return ledgerRepository.findAllAccounts();
    }

    public List<LedgerEntry> allEntries() {
        return ledgerRepository.findAllEntries();
    }

    /**
     * 守恒校验：
     * 1) 每个币种全部账户余额之和必须为 0（借贷恒等）；
     * 2) 每笔业务（同 notificationId）分录必须成对、等金额、跨两账户。
     */
    public boolean isConserved() {
        List<LedgerEntry> entries = ledgerRepository.findAllEntries();

        Map<String, Long> sumByCurrency = ledgerRepository.findAllAccounts().stream()
                .collect(Collectors.groupingBy(LedgerAccount::getCurrency,
                        Collectors.summingLong(LedgerAccount::getBalance)));
        for (Map.Entry<String, Long> e : sumByCurrency.entrySet()) {
            if (e.getValue() != 0) {
                log.error("CONSERVATION FAIL 币种={} 全部账户余额合计={}（应为0）", e.getKey(), e.getValue());
                return false;
            }
        }

        Map<String, List<LedgerEntry>> byNotification = entries.stream()
                .collect(Collectors.groupingBy(LedgerEntry::notificationId));
        for (Map.Entry<String, List<LedgerEntry>> e : byNotification.entrySet()) {
            List<LedgerEntry> group = e.getValue();
            if (group.size() != 2) {
                log.error("CONSERVATION FAIL notificationId={} 分录数={}（应为2）", e.getKey(), group.size());
                return false;
            }
            long debit = group.stream().filter(x -> x.direction() == LedgerDirection.DEBIT)
                    .mapToLong(LedgerEntry::amount).sum();
            long credit = group.stream().filter(x -> x.direction() == LedgerDirection.CREDIT)
                    .mapToLong(LedgerEntry::amount).sum();
            if (debit != credit || debit == 0) {
                log.error("CONSERVATION FAIL notificationId={} 借贷不等 debit={} credit={}",
                        e.getKey(), debit, credit);
                return false;
            }
        }
        log.info("CONSERVATION OK accounts={} entries={} currencies={}",
                ledgerRepository.findAllAccounts().size(), entries.size(), sumByCurrency.keySet());
        return true;
    }
}
