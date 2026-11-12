package com.english9x.analytics.reports;

import com.english9x.analytics.reports.ReportsDtos.ClassReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** F49: the report of a class, as JSON and as an Excel file with the same figures. */
@RestController
class ReportsController {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReportsService service;

    ReportsController(ReportsService service) {
        this.service = service;
    }

    @GetMapping("/api/orgs/{id}/classes/{cid}/report")
    @Operation(summary = "The report of a class, per learner",
            description = "Course progress, assignments (handed in / late / missing / pending), latest and best score, the weakest question types, attendance rate and study minutes. "
                    + "Only the figures of the organisation that owns the class; for its ORG_ADMIN or the class's teacher. 403 outside an organisation context or without the role; "
                    + "404 for a class of another organisation.")
    ResponseEntity<ClassReport> classReport(@PathVariable UUID id, @PathVariable UUID cid) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report(id, cid));
    }

    @GetMapping(value = "/api/orgs/{id}/classes/{cid}/report.xlsx", produces = XLSX)
    @Operation(summary = "The report of a class as an Excel file (same figures as the JSON)",
            description = "One sheet named after the class, one row per learner. Labels in `lang` (vi or en, default vi). Same access rules as the JSON report; the export is recorded in the audit log.",
            responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = XLSX, schema = @Schema(type = "string", format = "binary"))))
    ResponseEntity<byte[]> classReportExcel(@PathVariable UUID id, @PathVariable UUID cid, @RequestParam(defaultValue = "vi") String lang) {
        ClassReport report = service.export(id, cid);
        byte[] bytes = ReportWorkbook.write(report, ReportWorkbook.Labels.of(lang));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("class-report-" + cid + ".xlsx").build().toString()).body(bytes);
    }
}
