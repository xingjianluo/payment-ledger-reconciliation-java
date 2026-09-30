package com.github.xingjianluo.paymentledgerreconciliationjava.web;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.ReconcileDiff;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileDiffView;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileReport;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ReconcileRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.dto.ResolveDiffRequest;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.ReconcileService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reconcile")
public class ReconcileController {

    private final ReconcileService reconcileService;

    public ReconcileController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @PostMapping("/runs")
    public ReconcileReport run(@RequestBody ReconcileRequest request) {
        return reconcileService.reconcile(request);
    }

    @GetMapping("/diffs")
    public List<ReconcileDiffView> list(@RequestParam(required = false) String channel,
                                        @RequestParam(required = false) String reconDate,
                                        @RequestParam(defaultValue = "false") boolean openOnly) {
        return reconcileService.listDiffs(channel, reconDate, openOnly).stream()
                .map(ReconcileService::toView)
                .toList();
    }

    @PostMapping("/diffs/{diffKey}/resolutions")
    public ReconcileDiffView resolve(@PathVariable String diffKey,
                                     @RequestBody ResolveDiffRequest request) {
        ReconcileDiff diff = reconcileService.resolve(diffKey, request);
        return ReconcileService.toView(diff);
    }
}
