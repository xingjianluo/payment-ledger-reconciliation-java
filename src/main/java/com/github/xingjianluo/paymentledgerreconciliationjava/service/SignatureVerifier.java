package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** 本地签名工具：HMAC-SHA256 模拟渠道签名，不依赖外部服务。 */
@Service
public class SignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败: " + e.getMessage(), e);
        }
    }

    public boolean verify(String payload, String secret, String signature) {
        if (payload == null || secret == null || signature == null) {
            return false;
        }
        String expected = sign(payload, secret);
        // 长度不一致直接拒绝，避免短路差异；再做常量时间比较
        if (expected.length() != signature.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ signature.charAt(i);
        }
        return diff == 0;
    }
}
