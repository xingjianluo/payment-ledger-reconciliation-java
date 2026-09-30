package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelNotification;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.NotificationType;
import com.github.xingjianluo.paymentledgerreconciliationjava.simulator.LocalPaymentSimulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP 层：权限头、原因码与端到端链路。 */
class WebApiTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private LocalPaymentSimulator simulator;
    private final ObjectMapper json = new ObjectMapper();

    private MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void initMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void end_to_end_create_notify_reconcile_over_http() throws Exception {
        // 1) 无 API Key 建单被拒
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"W1","merchantId":"M001","channel":"MOCK_CHANNEL","amount":1000,"currency":"CNY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("PERMISSION_DENIED"));

        // 2) 带 Key 建单成功
        mvc.perform(post("/api/orders")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"W1","merchantId":"M001","channel":"MOCK_CHANNEL","amount":1000,"currency":"CNY"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));

        // 3) 成功回调
        ChannelNotification n = simulator.notification("WN1", "W1",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 1000L, "CNY");
        mvc.perform(post("/api/notifications")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(n)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        // 4) 重放：幂等
        MvcResult replay = mvc.perform(post("/api/notifications")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(n)))
                .andExpect(status().isOk()).andReturn();
        JsonNode replayBody = json.readTree(replay.getResponse().getContentAsString());
        assertTrue(replayBody.get("duplicate").asBoolean());
        assertEquals("DUPLICATE_NOTIFICATION", replayBody.get("reason").asText());

        // 5) 账本守恒
        mvc.perform(get("/api/ledger/conservation"))
                .andExpect(jsonPath("$.conserved").value(true));

        // 6) 对账（金额一致 -> MATCHED）
        String csv = "orderNo,merchantId,channel,amount,currency,status,occurredAt\n"
                + "W1,M001,MOCK_CHANNEL,1000,CNY,SUCCESS,1700000000000\n";
        mvc.perform(post("/api/reconciliation/batches/WB1/run-csv?channel=MOCK_CHANNEL")
                        .contentType(MediaType.TEXT_PLAIN).content(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details[0].discrepancyType").value("MATCHED"));
    }

    @Test
    void tampered_notification_returns_distinguishable_reason_over_http() throws Exception {
        mvc.perform(post("/api/orders")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"W2","merchantId":"M001","channel":"MOCK_CHANNEL","amount":1000,"currency":"CNY"}
                                """))
                .andExpect(status().isOk());
        ChannelNotification tampered = simulator.tamperSignature(simulator.notification("WN2", "W2",
                MERCHANT, CHANNEL, NotificationType.SUCCESS, 1000L, "CNY"));
        mvc.perform(post("/api/notifications")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(tampered)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("SIGNATURE_INVALID"));
    }
}
