package com.github.xingjianluo.paymentledgerreconciliationjava.simulator;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.MerchantDirectory;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.SignatureVerifier;
import org.springframework.stereotype.Component;

/** 本地支付渠道模拟器：生成带签名的通知，并可构造被篡改的异常通知用于测试。 */
@Component
public class LocalPaymentSimulator {

    private final MerchantDirectory merchants;
    private final SignatureVerifier signatures;

    public LocalPaymentSimulator(MerchantDirectory merchants, SignatureVerifier signatures) {
        this.merchants = merchants;
        this.signatures = signatures;
    }

    /** 生成一条字段完整、签名正确的渠道通知。 */
    public ChannelNotification notification(String notificationId, String orderNo, String merchantId,
                                            String channel, NotificationType type, long amount,
                                            String currency) {
        ChannelNotification n = new ChannelNotification();
        n.setNotificationId(notificationId);
        n.setOrderNo(orderNo);
        n.setMerchantId(merchantId);
        n.setChannel(channel);
        n.setType(type);
        n.setAmount(amount);
        n.setCurrency(currency);
        n.setStatus(type.name());
        String secret = merchants.secretOf(merchantId, channel);
        n.setSignature(signatures.sign(NotificationService.signaturePayload(n), secret));
        return n;
    }

    /** 篡改金额但保留旧签名（应被 AMOUNT_MISMATCH 拒绝；签名也会失效，按校验顺序先判来源签名）。 */
    public ChannelNotification tamperAmount(ChannelNotification n, long wrongAmount) {
        ChannelNotification copy = copy(n);
        copy.setAmount(wrongAmount);
        return copy;
    }

    public ChannelNotification tamperCurrency(ChannelNotification n, String wrongCurrency) {
        ChannelNotification copy = copy(n);
        copy.setCurrency(wrongCurrency);
        return copy;
    }

    public ChannelNotification tamperSignature(ChannelNotification n) {
        ChannelNotification copy = copy(n);
        copy.setSignature("deadbeef" + (n.getSignature() == null ? "" : n.getSignature().substring(0, 8)));
        return copy;
    }

    public ChannelNotification tamperMerchant(ChannelNotification n, String wrongMerchant) {
        ChannelNotification copy = copy(n);
        copy.setMerchantId(wrongMerchant);
        return copy;
    }

    public ChannelNotification copy(ChannelNotification n) {
        ChannelNotification c = new ChannelNotification();
        c.setNotificationId(n.getNotificationId());
        c.setOrderNo(n.getOrderNo());
        c.setMerchantId(n.getMerchantId());
        c.setChannel(n.getChannel());
        c.setType(n.getType());
        c.setAmount(n.getAmount());
        c.setCurrency(n.getCurrency());
        c.setStatus(n.getStatus());
        c.setSignature(n.getSignature());
        return c;
    }
}
