package com.english9x.analytics.aimonitor;

import com.english9x.analytics.aimonitor.AiMonitorDtos.Monitor;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** F56: SYS_ADMIN only. */
@RestController
class AiMonitorController {

    private final AiMonitorService service;

    AiMonitorController(AiMonitorService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/ai-monitor")
    @Operation(summary = "AI grading monitor (SYS_ADMIN only)",
            description = "Failed jobs, AI cost per day and per person, AI-vs-teacher deviation per question type, and the alert for days above e9x.grading.ai.daily-cost-alert. "
                    + "`from` / `to` are inclusive dates (Vietnam days), default the last 30 days, at most 366 days. 403 for anybody else.")
    ResponseEntity<Monitor> monitor(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.monitor(from, to));
    }
}
