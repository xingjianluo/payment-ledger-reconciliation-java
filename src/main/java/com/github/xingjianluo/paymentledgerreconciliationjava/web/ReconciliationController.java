package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelTransaction;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.DiscrepancyStatus;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ReconciliationDetail;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.ChannelDataParser;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 对账数据导入、批次查询与差异处理接口。 */
@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final ChannelDataParser channelDataParser;

    public ReconciliationController(ReconciliationService reconciliationService,
                                    ChannelDataParser channelDataParser) {
        this.reconciliationService = reconciliationService;
        this.channelDataParser = channelDataParser;
    }

    @PostMapping("/batches/{batchId}/run")
    public ReconciliationBatch run(@PathVariable String batchId,
                                   @RequestBody List<ChannelTransaction> transactions,
                                   @RequestParam(defaultValue = "MOCK_CHANNEL") String channel) {
        return reconciliationService.importAndRun(batchId, channel, transactions);
    }

    @PostMapping("/batches/{batchId}/run-csv")
    public ReconciliationBatch runCsv(@PathVariable String batchId,
                                      @RequestBody String csv,
                                      @RequestParam(defaultValue = "MOCK_CHANNEL") String channel) {
        List<ChannelTransaction> transactions = channelDataParser.parseCsv(csv);
        return reconciliationService.importAndRun(batchId, channel, transactions);
    }

    @GetMapping("/batches")
    public List<ReconciliationBatch> list() {
        return reconciliationService.listBatches();
    }

    @GetMapping("/batches/{batchId}")
    public ReconciliationBatch get(@PathVariable String batchId) {
        return reconciliationService.getBatch(batchId);
    }

    @PostMapping("/batches/{batchId}/details/{detailId}/handle")
    public ReconciliationDetail handle(@PathVariable String batchId, @PathVariable long detailId,
                                       @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? Map.of() : request;
        DiscrepancyStatus status = DiscrepancyStatus.HANDLED;
        Object rawStatus = req.get("status");
        if (rawStatus != null) {
            status = DiscrepancyStatus.valueOf(String.valueOf(rawStatus));
        }
        return reconciliationService.handleDiscrepancy(batchId, detailId, status,
                req.get("note") == null ? null : String.valueOf(req.get("note")),
                req.get("operator") == null ? null : String.valueOf(req.get("operator")),
                Boolean.TRUE.equals(req.get("force")));
    }
}
