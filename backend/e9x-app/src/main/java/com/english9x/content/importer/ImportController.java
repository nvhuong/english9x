package com.english9x.content.importer;

import com.english9x.content.media.api.StagedFilesException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * F11, the CMS import. Request-thread code like the media upload: the question type is a QUERY parameter on purpose, so the author is checked before the
 * framework reads the body (a form field would make it read the whole upload first).
 */
@RestController
class ImportController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    record ImportView(UUID id, String typeCode, String examCode, String status, String sourceName, boolean hasMedia, int totalRows, int processedRows,
                      int errorCount, int createdCount, String failureReason, boolean hasReport, Instant createdAt, Instant finishedAt) {}

    record ErrorView(int row, String column, String message) {}

    /** {@code errors} are the first ones (at most 1000); {@code errorCount} is how many there are. {@code versionIds}: the drafts of a successful import, in row order. */
    record ImportDetail(ImportView job, List<ErrorView> errors, List<UUID> versionIds) {}

    record Started(UUID jobId, String status) {}

    record Submitted(UUID bundleId) {}

    @Schema(name = "ImportUploadForm")
    record UploadForm(@Schema(type = "string", format = "binary", description = "the filled-in .xlsx made from the template") String file,
                      @Schema(type = "string", format = "binary", description = "optional .zip of the media files the rows name") String mediaZip) {}

    private final ImportService service;

    ImportController(ImportService service) {
        this.service = service;
    }

    @GetMapping("/api/cms/import/templates/{typeCode}.xlsx")
    @Operation(summary = "The Excel template of a question type, made from its JSON Schema",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    schema = @Schema(type = "string", format = "binary"))))
    ResponseEntity<byte[]> template(@PathVariable String typeCode) {
        ImportService.Template t = service.template(typeCode);
        return download(XLSX, t.filename(), t.bytes());
    }

    /**
     * {@code POST /api/cms/import?typeCode=TOEIC_P5_INCOMPLETE_SENTENCE}, multipart with the part {@code file} (the workbook) and optionally {@code mediaZip}.
     * Answers 202 with the id of the import; the work is done by a background job: poll {@code GET /api/cms/import/{jobId}}.
     */
    @PostMapping(path = "/api/cms/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @io.swagger.v3.oas.annotations.Parameter(name = "typeCode", in = io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, required = true,
            description = "the question type of every row; the caller must be an AUTHOR of its exam in the current context", schema = @Schema(type = "string"))
    @Operation(summary = "Start importing a filled-in template (all rows or none)",
            requestBody = @RequestBody(content = @Content(mediaType = "multipart/form-data", schema = @Schema(implementation = UploadForm.class))))
    ResponseEntity<Started> start(MultipartHttpServletRequest request) {
        String typeCode = UriComponentsBuilder.fromUriString("?" + (request.getQueryString() == null ? "" : request.getQueryString()))
                .build().getQueryParams().getFirst("typeCode");
        if (typeCode == null) {
            typeCode = request.getParameter("typeCode");
        } else {
            typeCode = java.net.URLDecoder.decode(typeCode, java.nio.charset.StandardCharsets.UTF_8);
        }
        UUID id;
        try {
            id = service.start(typeCode, request.getFile("file"), request.getFile("mediaZip"));
        } catch (StagedFilesException e) {
            throw ImportProblems.unavailable();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new Started(id, "QUEUED"));
    }

    @GetMapping("/api/cms/import")
    @Operation(summary = "My recent imports, newest first")
    ResponseEntity<List<ImportView>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recent().stream().map(ImportController::view).toList());
    }

    @GetMapping("/api/cms/import/{jobId}")
    @Operation(summary = "State of an import, its errors by row and column, and the drafts it made")
    ResponseEntity<ImportDetail> get(@PathVariable UUID jobId) {
        ImportService.Detail d = service.detail(jobId);
        List<ErrorView> errors = d.errors().stream().map(e -> new ErrorView(e.row(), e.column(), e.message())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ImportDetail(view(d.job()), errors, d.versionIds()));
    }

    @GetMapping("/api/cms/import/{jobId}/report.xlsx")
    @Operation(summary = "The workbook with the failed cells marked red",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    schema = @Schema(type = "string", format = "binary"))))
    ResponseEntity<byte[]> report(@PathVariable UUID jobId) {
        ImportService.Report r = service.report(jobId);
        return download(XLSX, r.filename(), r.bytes());
    }

    @PostMapping("/api/cms/import/{jobId}/submit")
    @Operation(summary = "Submit every draft of a successful import for review, as one bundle")
    ResponseEntity<Submitted> submit(@PathVariable UUID jobId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new Submitted(service.submit(jobId)));
    }

    private static ImportView view(ImportRepository.Job j) {
        return new ImportView(j.id(), j.typeCode(), j.examCode(), j.status(), j.sourceName(), j.hasMedia(), j.totalRows(), j.processedRows(), j.errorCount(),
                j.createdCount(), j.failureReason(), j.hasReport(), j.createdAt(), j.finishedAt());
    }

    private static ResponseEntity<byte[]> download(MediaType type, String filename, byte[] bytes) {
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }
}
