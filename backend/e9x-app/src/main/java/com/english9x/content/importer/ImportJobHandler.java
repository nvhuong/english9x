package com.english9x.content.importer;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.catalog.api.SchemaValidator;
import com.english9x.content.importer.Column.Side;
import com.english9x.content.importer.RowParser.MediaRef;
import com.english9x.content.importer.RowParser.Parsed;
import com.english9x.content.media.api.MediaImports;
import com.english9x.content.media.api.StagedFiles;
import com.english9x.content.question.api.QuestionDrafts;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.platform.jobs.api.WorkerJdbc;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;

/**
 * The job {@code excel_import}: all or nothing.
 * <ol>
 *   <li>the author's workbook (and zip) are read from the staging area; EVERY row is parsed and checked against the schemas of the type
 *       ({@link RowParser}), EVERY file a row names must be in the zip and must be the kind of file its column takes (sniffed and probed like an upload);
 *   <li>one error anywhere: the import is REJECTED, the errors are recorded (row, column, message), a copy of the workbook with the failed cells red is
 *       staged for download, and NOTHING is created (no media, no question);
 *   <li>no error: ONE transaction stores the media and makes every DRAFT version through the question service ({@link QuestionDrafts}: the same checks and
 *       the same row-level policies as the form), links them to the import, and marks it SUCCEEDED. A refusal from the question service for any row rolls the
 *       whole transaction back and the import is REJECTED with that row's reasons. There is NO review bundle yet: the author submits the lot later.
 * </ol>
 * Runs as the worker thread with the uploader's context bound ({@code CurrentContextHolder}), so the application role and its row-level security do all
 * the reading and writing. When the retries are used up, {@link #onExhausted} marks the import FAILED through the worker's one narrow door
 * ({@code import_job_mark_failed}), which needs no user binding: a member removed from the organisation cannot be bound any more.
 */
@JobHandler(ImportJobHandler.TYPE)
class ImportJobHandler implements JobExecutor {

    static final String TYPE = "excel_import";

    private static final Logger LOG = LoggerFactory.getLogger(ImportJobHandler.class);

    private final PlatformTransactionManager tm;
    private final ImportRepository repository;
    private final QuestionTypes types;
    private final SchemaValidator schemas;
    private final MediaImports media;
    private final StagedFiles staged;
    private final QuestionDrafts drafts;
    private final ImportProperties props;
    private final WorkerJdbc worker;
    private final ObjectMapper json;

    ImportJobHandler(PlatformTransactionManager tm, ImportRepository repository, QuestionTypes types, SchemaValidator schemas, MediaImports media,
                     StagedFiles staged, QuestionDrafts drafts, ImportProperties props, WorkerJdbc worker, ObjectMapper json) {
        this.tm = tm;
        this.repository = repository;
        this.types = types;
        this.schemas = schemas;
        this.media = media;
        this.staged = staged;
        this.drafts = drafts;
        this.props = props;
        this.worker = worker;
        this.json = json;
    }

    static String key(UUID importId, String name) {
        return "staging/imports/" + importId + "/" + name;
    }

    // ---- the job -------------------------------------------------------------------------------------------------------------------

    @Override
    public JobResult handle(Job job) throws Exception {
        UUID importId = UUID.fromString(job.payload().path("importId").asText());
        UUID userId = UUID.fromString(job.payload().path("userId").asText());
        String org = job.payload().path("orgId").asText("");
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(userId, org.isBlank() ? null : UUID.fromString(org))) {
            TransactionTemplate tx = new TransactionTemplate(tm);
            // Binding a person who is no longer in the organisation fails right here (OrgContextDeniedException): the job fails, is retried, and is marked
            // FAILED by onExhausted, which does not bind anyone.
            Optional<ImportRepository.Job> found = tx.execute(s -> repository.find(importId));
            if (found == null || found.isEmpty()) {
                return JobResult.failure("import-not-visible"); // gone, or its uploader is no longer an AUTHOR of the exam there
            }
            ImportRepository.Job row = found.get();
            if (row.finished()) {
                return JobResult.success(); // an earlier run completed it
            }
            Boolean started = tx.execute(s -> repository.markRunning(importId));
            if (!Boolean.TRUE.equals(started)) {
                return JobResult.success();
            }
            run(row, tx);
            return JobResult.success();
        }
    }

    @Override
    public void onExhausted(Job job, String reason) {
        UUID importId = UUID.fromString(job.payload().path("importId").asText());
        worker.jdbc().queryForObject("select import_job_mark_failed(?, ?)", Boolean.class, importId, "the import could not be run");
        cleanInputs(importId);
    }

    private void run(ImportRepository.Job row, TransactionTemplate tx) throws Exception {
        UUID id = row.id();
        QuestionTypeDef def = types.get(row.typeCode());
        ColumnPlan plan = ColumnPlanner.plan(def);
        Path dir = Files.createTempDirectory("e9x-import-");
        try {
            Path xlsx = dir.resolve("data.xlsx");
            staged.get(key(id, "data.xlsx"), xlsx);

            Workbooks.Sheet2 sheet;
            try {
                sheet = Workbooks.read(xlsx, props.maxRows());
            } catch (Workbooks.Unusable e) {
                reject(tx, id, xlsx, List.of(new RowError(0, null, e.getMessage())), false, dir);
                return;
            }
            List<RowError> errors = new ArrayList<>();
            checkHeaders(plan, sheet, errors);
            progress(tx, id, sheet.totalRows(), 0);
            if (sheet.rows().isEmpty() && errors.isEmpty()) {
                errors.add(new RowError(0, null, "file không có dòng dữ liệu nào"));
            }

            ZipMedia zip = null;
            try {
                if (row.hasMedia()) {
                    Path z = dir.resolve("media.zip");
                    staged.get(key(id, "media.zip"), z);
                    try {
                        zip = ZipMedia.open(z, props.maxMediaSize().toBytes());
                    } catch (ZipMedia.Unusable e) {
                        errors.add(new RowError(0, null, e.getMessage()));
                    }
                }
                RowParser parser = new RowParser(plan, schemas);
                List<Parsed> parsed = new ArrayList<>();
                int done = 0;
                for (Workbooks.DataRow r : sheet.rows()) {
                    RowParser.Result result = parser.parse(r.number(), r.cells());
                    errors.addAll(result.errors());
                    if (result.parsed() != null) {
                        parsed.add(result.parsed());
                    }
                    if (++done % 250 == 0) {
                        progress(tx, id, sheet.totalRows(), done);
                    }
                }
                errors.addAll(checkMedia(parsed, zip, dir));
                if (!errors.isEmpty()) {
                    reject(tx, id, xlsx, errors, !(errors.size() == 1 && errors.get(0).row() == 0), dir);
                    return;
                }
                progress(tx, id, sheet.totalRows(), sheet.totalRows());
                create(tx, row, def, parsed, zip, xlsx, dir);
            } finally {
                if (zip != null) {
                    zip.close();
                }
            }
        } finally {
            deleteTree(dir);
        }
    }

    // ---- checks ---------------------------------------------------------------------------------------------------------------------

    private static void checkHeaders(ColumnPlan plan, Workbooks.Sheet2 sheet, List<RowError> errors) {
        Set<String> known = new LinkedHashSet<>();
        plan.headers().forEach(h -> known.add(h.toLowerCase(java.util.Locale.ROOT)));
        Set<String> seen = new LinkedHashSet<>();
        for (String h : sheet.headers()) {
            if (h.isEmpty()) {
                continue;
            }
            String lower = h.toLowerCase(java.util.Locale.ROOT);
            if (!known.contains(lower)) {
                errors.add(new RowError(1, h, "cột không có trong mẫu của dạng câu này"));
            } else if (!seen.add(lower)) {
                errors.add(new RowError(1, h, "cột bị lặp"));
            }
        }
    }

    /** Every file a row names must be in the zip, once, and be what its column takes. Each file is looked at once, however many rows use it. */
    private List<RowError> checkMedia(List<Parsed> parsed, ZipMedia zip, Path dir) throws IOException {
        List<RowError> errors = new ArrayList<>();
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        for (Parsed p : parsed) {
            for (MediaRef ref : p.media()) {
                if (zip == null) {
                    errors.add(new RowError(p.row(), ref.column(), "có tên tệp media nhưng không tải lên file zip media"));
                    continue;
                }
                Verdict v = verdicts.computeIfAbsent(ref.filename(), n -> look(n, zip, dir));
                if (v.problem != null) {
                    errors.add(new RowError(p.row(), ref.column(), v.problem));
                } else if (ref.wantedKind() != null && !ref.wantedKind().equals(v.kind)) {
                    errors.add(new RowError(p.row(), ref.column(), "tệp '" + ref.filename() + "' là " + v.kind.toLowerCase(java.util.Locale.ROOT)
                            + ", cột này cần " + ref.wantedKind().toLowerCase(java.util.Locale.ROOT)));
                }
            }
        }
        return errors;
    }

    private record Verdict(String problem, String kind) {}

    private Verdict look(String name, ZipMedia zip, Path dir) {
        if (zip.isAmbiguous(name)) {
            return new Verdict("tên tệp '" + name + "' xuất hiện nhiều lần trong file zip media", null);
        }
        if (!zip.contains(name)) {
            return new Verdict("không tìm thấy tệp '" + name + "' trong file zip media", null);
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, "m-", ".bin");
            zip.extract(name, tmp);
            StringBuilder refusal = new StringBuilder();
            MediaImports.Inspection i = media.inspect(tmp, name, refusal);
            return i == null ? new Verdict("tệp '" + name + "' không dùng được: " + refusal, null) : new Verdict(null, i.kind().name());
        } catch (ZipMedia.Unusable e) {
            return new Verdict(e.getMessage(), null);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the media zip", e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    // ---- the two ends ---------------------------------------------------------------------------------------------------------------

    private record Outcome(List<RowError> errors) {}

    private void create(TransactionTemplate tx, ImportRepository.Job row, QuestionTypeDef def, List<Parsed> parsed, ZipMedia zip, Path xlsx, Path dir) {
        UUID id = row.id();
        List<UUID> storedMedia = new ArrayList<>();
        Outcome outcome;
        try {
            outcome = tx.execute(st -> {
                Map<String, UUID> ids = new LinkedHashMap<>();
                for (Parsed p : parsed) {
                    for (MediaRef ref : p.media()) {
                        ids.computeIfAbsent(ref.filename(), name -> storeFile(def, name, zip, dir, storedMedia));
                    }
                }
                List<RowError> errs = new ArrayList<>();
                int position = 0;
                for (Parsed p : parsed) {
                    for (MediaRef ref : p.media()) {
                        RowParser.substitute(p, ref, ids.get(ref.filename()));
                    }
                    try {
                        UUID version = drafts.create(new QuestionDrafts.Draft(def.code(), p.content(), p.answerKey(), p.explanation(), p.points()));
                        repository.addVersion(id, ++position, p.row(), version);
                    } catch (ErrorResponseException e) {
                        if (e.getStatusCode().value() != HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                            throw e; // a conflict or an outage, not a verdict on the row: the job fails and is retried
                        }
                        errs.addAll(rowErrors(p.row(), e));
                    }
                }
                if (!errs.isEmpty()) {
                    st.setRollbackOnly();
                    return new Outcome(errs);
                }
                if (!repository.succeed(id, position)) {
                    throw new IllegalStateException("the import is no longer running"); // marked FAILED meanwhile: undo everything
                }
                return new Outcome(List.of());
            });
        } catch (RuntimeException e) {
            storedMedia.forEach(media::discard);
            throw e;
        }
        if (outcome != null && !outcome.errors().isEmpty()) {
            storedMedia.forEach(media::discard); // the rows were rolled back with the transaction; the objects are not part of it
            reject(tx, id, xlsx, outcome.errors(), true, dir);
            return;
        }
        cleanInputs(id);
    }

    private UUID storeFile(QuestionTypeDef def, String name, ZipMedia zip, Path dir, List<UUID> storedMedia) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, "m-", ".bin");
            zip.extract(name, tmp);
            UUID mediaId = media.store(def.examCode(), tmp, name);
            storedMedia.add(mediaId);
            return mediaId;
        } catch (ZipMedia.Unusable | IOException e) {
            throw new IllegalStateException("cannot read the media zip", e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private List<RowError> rowErrors(int row, ErrorResponseException e) {
        List<RowError> out = new ArrayList<>();
        Object errors = e.getBody().getProperties() == null ? null : e.getBody().getProperties().get("errors");
        if (errors != null) {
            for (Map<String, Object> m : json.convertValue(errors, new TypeReference<List<Map<String, Object>>>() {})) {
                out.add(new RowError(row, null, String.valueOf(m.getOrDefault("field", "")) + ": " + m.getOrDefault("message", "không hợp lệ")));
            }
        }
        if (out.isEmpty()) {
            out.add(new RowError(row, null, "câu hỏi không hợp lệ"));
        }
        return out;
    }

    private void progress(TransactionTemplate tx, UUID id, int total, int processed) {
        tx.executeWithoutResult(s -> repository.progress(id, total, processed));
    }

    /** Records the errors and, when the workbook can be marked, stages the red copy; nothing was created. */
    private void reject(TransactionTemplate tx, UUID id, Path xlsx, List<RowError> errors, boolean report, Path dir) {
        List<RowError> sorted = new ArrayList<>(errors);
        sorted.sort(Comparator.comparingInt(RowError::row));
        boolean hasReport = false;
        if (report) {
            Path out = dir.resolve("report.xlsx");
            try (OutputStream os = Files.newOutputStream(out)) {
                Workbooks.writeReport(xlsx, sorted, os);
                staged.put(key(id, "report.xlsx"), out, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                hasReport = true;
            } catch (IOException | RuntimeException e) {
                LOG.warn("the error report of import {} could not be made: {}", id, e.getClass().getSimpleName());
            }
        }
        boolean withReport = hasReport;
        Boolean moved = tx.execute(s -> repository.reject(id, sorted, withReport));
        if (!Boolean.TRUE.equals(moved)) {
            throw new IllegalStateException("the import is no longer running");
        }
        cleanInputs(id);
    }

    private void cleanInputs(UUID id) {
        staged.deleteQuietly(key(id, "data.xlsx"));
        staged.deleteQuietly(key(id, "media.zip"));
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // a temp file; the directory is removed at the end
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(ImportJobHandler::deleteQuietly);
        } catch (IOException e) {
            LOG.warn("temp directory {} could not be removed", dir.getFileName());
        }
    }
}
