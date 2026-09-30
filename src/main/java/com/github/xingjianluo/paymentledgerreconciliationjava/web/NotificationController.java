package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.dto.NotificationResult;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.PaymentNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.NotificationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public NotificationResult receive(@RequestBody PaymentNotification notification) {
        return notificationService.handle(notification);
    }
}
