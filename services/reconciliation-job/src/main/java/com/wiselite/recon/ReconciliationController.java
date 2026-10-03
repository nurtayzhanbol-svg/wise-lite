package com.wiselite.recon;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping("/reconciliation/runs")
    public ReconciliationService.Report run() {
        return service.run();
    }

    @GetMapping("/reconciliation/runs/{id}/breaks")
    public List<Break> breaks(@PathVariable UUID id) {
        return service.breaks(id);
    }
}
