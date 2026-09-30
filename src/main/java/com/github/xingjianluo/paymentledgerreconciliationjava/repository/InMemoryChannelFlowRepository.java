package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ChannelFlow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存渠道流水仓储 */
@Repository
public class InMemoryChannelFlowRepository implements ChannelFlowRepository {

    private final ConcurrentHashMap<String, ChannelFlow> store = new ConcurrentHashMap<>();

    @Override
    public ChannelFlow save(ChannelFlow flow) {
        store.put(flow.getChannelFlowId(), flow);
        return flow;
    }

    @Override
    public List<ChannelFlow> findByChannel(String channel) {
        return store.values().stream()
                .filter(f -> channel == null || channel.equals(f.getChannel()))
                .sorted(Comparator.comparing(ChannelFlow::getChannelFlowId))
                .toList();
    }

    @Override
    public void deleteByChannelAndDate(String channel, String reconDate) {
        LocalDate date = LocalDate.parse(reconDate);
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        store.values().removeIf(f ->
                f.getChannel().equals(channel)
                        && !f.getOccurredAt().isBefore(from)
                        && f.getOccurredAt().isBefore(to));
    }
}
