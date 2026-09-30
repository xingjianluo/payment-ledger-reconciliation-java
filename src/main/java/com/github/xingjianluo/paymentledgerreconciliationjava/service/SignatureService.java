package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地模拟签名服务：HmacSHA256(secret, payload) 的十六进制结果。
 * 密钥按渠道在内存中配置，无外部依赖。签名串固定为
 * channel|channelNotificationId|orderId|amount|currency|type。
 */
@Service
public class SignatureService {

    private final Map<String, String> channelSecrets = new ConcurrentHashMap<>();

    public void registerChannel(String channel, String secret) {
        channelSecrets.put(channel, secret);
    }

    public boolean knowsChannel(String channel) {
        return channel != null && channelSecrets.containsKey(channel);
    }

    public String sign(String channel, String payload) {
        String secret = channelSecrets.get(channel);
        if (secret == null) {
            throw new IllegalArgumentException("unknown channel: " + channel);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("sign failed", e);
        }
    }

    public boolean verify(String channel, String payload, String sign) {
        if (sign == null) {
            return false;
        }
        String expected;
        try {
            expected = sign(channel, payload);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
