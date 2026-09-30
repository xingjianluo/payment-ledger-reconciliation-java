package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 支付单与回调通知接口。 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;
    private final NotificationService notificationService;

    public PaymentController(PaymentService paymentService, NotificationService notificationService) {
        this.paymentService = paymentService;
        this.notificationService = notificationService;
    }

    @PostMapping("/orders")
    public PaymentOrder createOrder(@RequestBody Map<String, Object> request,
                                    @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return paymentService.createOrder(
                (String) request.get("orderNo"),
                (String) request.get("merchantId"),
                (String) request.get("channel"),
                ((Number) request.get("amount")).longValue(),
                (String) request.get("currency"),
                apiKey);
    }

    @GetMapping("/orders/{orderNo}")
    public PaymentOrder getOrder(@PathVariable String orderNo) {
        return paymentService.getOrder(orderNo);
    }

    @PostMapping("/notifications")
    public NotificationService.NotificationResult receiveNotification(
            @RequestBody ChannelNotification notification,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return notificationService.handle(notification, apiKey);
    }
}
