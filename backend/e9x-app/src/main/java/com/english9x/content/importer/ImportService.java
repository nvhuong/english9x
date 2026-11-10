package com.english9x.content.importer;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.content.media.api.StagedFiles;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.platform.jobs.api.JobQueue;
import com.english9x.workflow.api.BundleRef;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The request side of the Excel import (F11). Order: who (an AUTHOR of the type's exam in the current context, asked of the database) before a byte of the
 * body is read; then the files (size, and that they are what they say: the zip magic number); then the files are staged, and the import row and the job are written
 * in ONE transaction (the job exists only if the row does). Nothing is parsed here: the job does that, with the author's context bound.
 */
@Service
class ImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final Authz authz;
    private final QuestionTypes types;
    private final TemplateGenerator templates;
    private final StagedFiles staged;
    private final ImportRepository repository;
    private final JobQueue queue;
    private final ReviewService reviews;
    private final ImportProperties props;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    ImportService(Authz authz, QuestionTypes types, TemplateGenerator templates, StagedFiles staged, ImportRepository repository, JobQueue queue,
                  ReviewService reviews, ImportProperties props, ObjectMapper json, org.springframework.transaction.PlatformTransactionManager tm) {
        this.authz = authz;
        this.types = types;
        this.templates = templates;
        this.staged = staged;
        this.repository = repository;
        this.queue = queue;
        this.reviews = reviews;
        this.props = props;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    record Template(String filename, byte[] bytes) {}

    record Report(String filename, byte[] bytes) {}

    private QuestionTypeDef importable(String typeCode) {
        QuestionTypeDef def = typeCode == null ? null : types.find(typeCode).orElse(null);
        if (def == null || def.excelTemplateId().isEmpty()) {
            throw ImportProblems.unknownType();
        }
        authz.require(Role.AUTHOR, Optional.of(def.examCode()));
        return def;
    }

    Template template(String typeCode) {
        QuestionTypeDef def = importable(typeCode);
        return new Template(def.code() + ".xlsx", templates.generate(def));
    }

    UUID start(String typeCode, MultipartFile file, MultipartFile zip) {
        QuestionTypeDef def = importable(typeCode);
        CurrentContextHolder.Binding who = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no signed-in user"));
        if (file == null || file.isEmpty()) {
            throw ImportProblems.fileMissing();
        }
        boolean hasZip = zip != null && !zip.isEmpty();
        if (file.getSize() > ImportProperties.MAX_WORKBOOK_SIZE.toBytes()) {
            throw ImportProblems.tooLarge("workbook", ImportProperties.MAX_WORKBOOK_SIZE.toString());
        }
        if (hasZip && zip.getSize() > props.maxMediaSize().toBytes()) {
            throw ImportProblems.tooLarge("media zip", props.maxMediaSize().toString());
        }
        UUID id = UUID.randomUUID();
        Path dir = null;
        boolean staged1 = false;
        try {
            dir = Files.createTempDirectory("e9x-upload-");
            Path xlsx = dir.resolve("data.xlsx");
            file.transferTo(xlsx);
            if (!isZip(xlsx)) {
                throw ImportProblems.notWorkbook();
            }
            Path z = dir.resolve("media.zip");
            if (hasZip) {
                zip.transferTo(z);
                if (!isZip(z)) {
                    throw ImportProblems.notZip();
                }
            }
            staged1 = true;
            staged.put(ImportJobHandler.key(id, "data.xlsx"), xlsx, XLSX);
            if (hasZip) {
                staged.put(ImportJobHandler.key(id, "media.zip"), z, "application/zip");
            }
            String name = displayName(file.getOriginalFilename());
            tx.executeWithoutResult(s -> {
                repository.insert(id, who.orgId().orElse(null), who.userId(), def.examCode(), def.code(), name, hasZip);
                ObjectNode payload = json.createObjectNode().put("importId", id.toString()).put("userId", who.userId().toString());
                who.orgId().ifPresent(o -> payload.put("orgId", o.toString()));
                queue.enqueue(ImportJobHandler.TYPE, payload, null);
            });
            return id;
        } catch (IOException e) {
            LOG.error("import upload could not be handled", e);
            throw ImportProblems.unavailable();
        } catch (RuntimeException e) {
            if (staged1) {
                cleanup(id);
            }
            if (e instanceof com.english9x.content.media.api.StagedFilesException) {
                throw ImportProblems.unavailable();
            }
            throw e;
        } finally {
            deleteTree(dir);
        }
    }

    /** One import, its errors and its drafts, read in one transaction so row-level security answers once and the three parts agree. */
    record Detail(ImportRepository.Job job, List<RowError> errors, List<UUID> versionIds) {}

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    Detail detail(UUID id) {
        ImportRepository.Job job = repository.find(id).orElseThrow(ImportProblems::notFound);
        return new Detail(job, repository.errors(id), repository.versionIds(id));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    List<ImportRepository.Job> recent() {
        return repository.recent(50);
    }

    Report report(UUID id) {
        ImportRepository.Job job = tx.execute(s -> repository.find(id).orElse(null));
        if (job == null) {
            throw ImportProblems.notFound();
        }
        if (!job.hasReport()) {
            throw ImportProblems.noReport();
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("e9x-report-");
            Path out = dir.resolve("report.xlsx");
            staged.get(ImportJobHandler.key(id, "report.xlsx"), out);
            String base = job.sourceName().replaceAll("(?i)\\.xlsx$", "");
            return new Report("loi-" + base + ".xlsx", Files.readAllBytes(out));
        } catch (IOException e) {
            throw ImportProblems.unavailable();
        } finally {
            deleteTree(dir);
        }
    }

    /** Submits the whole lot for review as ONE bundle (spec 7.1): the workflow creates the review_bundles row now, not before. */
    @org.springframework.transaction.annotation.Transactional
    UUID submit(UUID id) {
        ImportRepository.Job job = repository.find(id).orElseThrow(ImportProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(job.examCode()));
        List<UUID> versions = repository.versionIds(id);
        if (!job.status().equals("SUCCEEDED") || versions.isEmpty()) {
            throw ImportProblems.notSubmittable();
        }
        return reviews.submit(new BundleRef(versions.stream().map(v -> new VersionRef(QuestionVersions.REVIEW_TYPE, v)).toList()));
    }

    private void cleanup(UUID id) {
        staged.deleteQuietly(ImportJobHandler.key(id, "data.xlsx"));
        staged.deleteQuietly(ImportJobHandler.key(id, "media.zip"));
    }

    private static boolean isZip(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        }
    }

    private static String displayName(String raw) {
        String name = raw == null ? "" : raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "import.xlsx";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static void deleteTree(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // temp files only
        }
    }
}
