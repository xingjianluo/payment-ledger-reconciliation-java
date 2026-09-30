package com.github.xingjianluo.paymentledgerreconciliationjava.repo;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelTransaction;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 渠道流水输入快照：批次首次运行时固化，重跑使用同一份输入保证结果稳定。 */
@Repository
public class ChannelDataSnapshotRepository {

    private final ConcurrentHashMap<String, List<ChannelTransaction>> snapshots = new ConcurrentHashMap<>();

    /**
     * 首次调用固化输入并返回它；之后用同一 batchId 再次提交（即使内容不同）也返回首次快照，
     * 从而保证对账可重复执行、结论稳定。
     */
    public List<ChannelTransaction> saveIfAbsent(String batchId, List<ChannelTransaction> transactions) {
        List<ChannelTransaction> frozen = List.copyOf(transactions);
        List<ChannelTransaction> existing = snapshots.putIfAbsent(batchId, frozen);
        return existing == null ? frozen : existing;
    }

    /** 测试隔离。 */
    public void reset() {
        snapshots.clear();
    }

    public List<ChannelTransaction> get(String batchId) {
        return snapshots.get(batchId);
    }
}
