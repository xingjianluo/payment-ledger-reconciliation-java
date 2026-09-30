package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.enums.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ChannelFlow;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.PaymentOrder;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.CreateOrderRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.PaymentNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.LocalChannelSimulator;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.PaymentService;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.ReconcileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 端到端：下单 -> 回调成功 -> 重放 -> 退款 -> 对账重跑，全程 HTTP，失败原因可区分 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EndToEndIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired PaymentService paymentService;
    @Autowired LocalChannelSimulator simulator;
    @Autowired ReconcileService reconcileService;

    @Test
    void full_journey_over_http() throws Exception {
        // 1. 建单
        MvcResult created = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateOrderRequest(
                                "MCH-E2E", "ACC-CNY-001", "MOCK_ALIPAY", "CNY", 50_000L))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode orderNode = om.readTree(created.getResponse().getContentAsString());
        String orderId = orderNode.get("orderId").asText();
        assertEquals("CREATED", orderNode.get("status").asText());
        PaymentOrder order = paymentService.getOrder(orderId);

        // 2. CREATED 阶段伪造签名：Webhook 正常响应 200，结果体给可区分原因
        PaymentNotification forgedNewId = new PaymentNotification("MOCK_ALIPAY",
                "E2E-FORGED", orderId, "MCH-E2E",
                NotificationType.PAYMENT_SUCCESS.name(), 50_000L, "CNY", "abc123");
        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(forgedNewId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("SIGNATURE_INVALID"));

        // 3. 成功回调
        PaymentNotification success = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "E2E-N1");
        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(success)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.orderStatus").value("SUCCESS"));

        // 4. 重放同一回调：幂等
        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(success)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IDEMPOTENT"));

        // 5. 非法状态跳转：SUCCESS 上再来一次成功
        PaymentNotification secondSuccess = simulator.buildNotification(
                order, NotificationType.PAYMENT_SUCCESS, "E2E-N2");
        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(secondSuccess)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("INVALID_STATUS_TRANSITION"));

        // 6. 退款
        PaymentNotification refund = simulator.buildNotification(
                order, NotificationType.REFUND, "E2E-N3");
        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(refund)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("REFUNDED"));

        // 7. 账本守恒：流水有符号合计 = 余额合计 - 初始两账户注资(2_000_000)
        MvcResult report = mvc.perform(get("/api/ledger/report"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ledger = om.readTree(report.getResponse().getContentAsString());
        long entriesSum = ledger.get("sumSignedEntries").asLong();
        long balanceSum = 0L;
        for (JsonNode a : ledger.get("accounts")) {
            balanceSum += a.get("balance").asLong();
        }
        assertEquals(balanceSum - 2_000_000L, entriesSum);
        assertEquals(0L, entriesSum, "paid then fully refunded => net zero");

        // 8. 对账：渠道仍显示 SUCCESS，本地已 REFUNDED => STATUS_MISMATCH；重跑结果稳定
        ChannelFlow flow = simulator.buildFlow(
                paymentService.getOrder(orderId), "SUCCESS", "FLW-E2E");
        List<String> csv = List.of(simulator.flowToCsv(flow));
        ReconcileRequest req = new ReconcileRequest("MOCK_ALIPAY", "2026-09-30", csv);
        var first = reconcileService.reconcile(req);
        var second = reconcileService.reconcile(req);
        assertEquals(1, first.diffCount());
        assertEquals(1, second.diffCount());
        assertEquals("STATUS_MISMATCH", first.diffs().get(0).type().name());
        assertEquals(first.diffs().get(0).diffKey(), second.diffs().get(0).diffKey());
    }
}
