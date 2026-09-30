package com.github.xingjianluo.paymentledgerreconciliationjava.repository;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ChannelFlow;

import java.util.List;

public interface ChannelFlowRepository {
    ChannelFlow save(ChannelFlow flow);
    List<ChannelFlow> findByChannel(String channel);
    void deleteByChannelAndDate(String channel, String reconDate);
}
