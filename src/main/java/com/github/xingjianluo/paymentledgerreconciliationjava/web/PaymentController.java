package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.CreateOrderRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.OrderView;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(@RequestBody CreateOrderRequest request) {
        return toView(paymentService.createOrder(request));
    }

    @GetMapping("/{orderId}")
    public OrderView get(@PathVariable String orderId) {
        return toView(paymentService.getOrder(orderId));
    }

    private OrderView toView(PaymentOrder o) {
        return new OrderView(o.getOrderId(), o.getMerchantId(), o.getAccountId(),
                o.getChannel(), o.getCurrency(), o.getAmount(), o.getStatus(), o.getVersion());
    }
}
