package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 本地商户目录：维护商户可用渠道与回调密钥，用于来源与权限校验。 */
@Service
public class MerchantDirectory {

    /** 一个商户在一个渠道上的凭证。 */
    private record ChannelCredential(String secret, String apiKey) {
    }

    private final Map<String, Map<String, ChannelCredential>> merchants = new ConcurrentHashMap<>();

    public void registerMerchant(String merchantId, String channel, String secret, String apiKey) {
        merchants.computeIfAbsent(merchantId, k -> new ConcurrentHashMap<>())
                .put(channel, new ChannelCredential(secret, apiKey));
    }

    public boolean merchantExists(String merchantId) {
        return merchantId != null && merchants.containsKey(merchantId);
    }

    public boolean supportsChannel(String merchantId, String channel) {
        Map<String, ChannelCredential> channels = merchants.get(merchantId);
        return channels != null && channels.containsKey(channel);
    }

    public String secretOf(String merchantId, String channel) {
        Map<String, ChannelCredential> channels = merchants.get(merchantId);
        if (channels == null) {
            return null;
        }
        ChannelCredential credential = channels.get(channel);
        return credential == null ? null : credential.secret();
    }

    public boolean isValidApiKey(String merchantId, String apiKey) {
        if (merchantId == null || apiKey == null) {
            return false;
        }
        Map<String, ChannelCredential> channels = merchants.get(merchantId);
        if (channels == null) {
            return false;
        }
        return channels.values().stream().anyMatch(c -> c.apiKey().equals(apiKey));
    }

    public Set<String> knownMerchants() {
        return Set.copyOf(merchants.keySet());
    }
}
