package com.english9x.content.skill.internal;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.content.skill.api.GradingSkillVersionView;
import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.media.api.MediaFiles;
import com.english9x.content.media.api.MediaKind;
import com.english9x.content.media.api.StagedFilesException;
import com.english9x.content.skill.api.GradingSkillVersions;
import com.english9x.content.skill.api.SkillDryRunner;
import com.english9x.content.skill.internal.SkillProblems.FieldProblem;
import com.english9x.content.skill.internal.SkillRepository.Content;
import com.english9x.content.skill.internal.SkillRepository.Header;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Grading skills (F16): the rubric, criteria, prompt template, samples and result schema the AI grades a Writing or Speaking answer with. Same rules as
 * questions, lessons and score tables, in the same order: who (an AUTHOR of the skill's exam in the current context; the policies ask again), what state (only
 * the latest version decides), what content ({@link SkillValidator}). One skill per owner and question type.
 */
@Service
class SkillService {

    /** @param questionTypeCode used when the skill is made; on update it may be null, and when given must match (it never changes) */
    record Body(String questionTypeCode, String rubric, JsonNode criteria, String promptTemplate, JsonNode samples, JsonNode resultSchema) {}

    record Saved(GradingSkillVersionView view, boolean newVersion) {}

    record Page(List<SkillRepository.Summary> items, long total, int offset, int limit) {}

    /** What a dry run is asked to grade: the text of an answer, or a recording; {@code task} is the question (optional). */
    record DryRun(String response, UUID mediaId, String task) {}

    /** The question the model is told about when the dry run names none. */
    static final String DEFAULT_TASK = "(dry run: no question was given; grade the answer against the rubric)";

    private static final int PAGE = 50;

    private final Authz authz;
    private final QuestionTypes types;
    private final ReviewService reviews;
    private final SkillRepository repository;
    private final SkillValidator validator;
    private final ObjectProvider<SkillDryRunner> runner;
    private final MediaFiles media;
    private final TransactionTemplate readOnly;

    SkillService(Authz authz, QuestionTypes types, ReviewService reviews, SkillRepository repository, SkillValidator validator, ObjectProvider<SkillDryRunner> runner,
            MediaFiles media, PlatformTransactionManager transactions) {
        this.authz = authz;
        this.types = types;
        this.reviews = reviews;
        this.repository = repository;
        this.validator = validator;
        this.runner = runner;
        this.media = media;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Transactional
    GradingSkillVersionView create(Body body) {
        if (body.questionTypeCode() == null || body.questionTypeCode().isBlank()) {
            throw SkillProblems.invalid(List.of(new FieldProblem("questionTypeCode", "is required")));
        }
        Optional<QuestionTypeDef> type = types.find(body.questionTypeCode());
        if (type.isEmpty() || type.get().kind().autoScored()) {
            // an unknown type, or one the answer key scores by itself: nothing for an AI to grade (not secret: the catalog is public)
            authz.require(Role.AUTHOR, Optional.empty());
            throw SkillProblems.invalid(List.of(new FieldProblem("questionTypeCode", type.isEmpty() ? "is not a question type of the catalog"
                    : "is scored from its answer key; only Writing and Speaking types are graded with a skill")));
        }
        String exam = type.get().examCode();
        authz.require(Role.AUTHOR, Optional.of(exam));
        CurrentContextHolder.Binding who = binding();
        SkillValidator.Checked checked = validator.validate(exam, body.rubric(), body.criteria(), body.promptTemplate(), body.samples(), body.resultSchema());
        if (!checked.errors().isEmpty()) {
            throw SkillProblems.invalid(checked.errors());
        }
        UUID owner = who.orgId().orElse(null);
        Optional<Header> existing = repository.forType(body.questionTypeCode(), owner);
        if (existing.isPresent()) {
            throw SkillProblems.exists(existing.get().id());
        }
        Header header = new Header(UUID.randomUUID(), owner, exam, body.questionTypeCode());
        UUID versionId = UUID.randomUUID();
        try {
            repository.insertHeader(header);
            repository.insertVersion(versionId, header, 1, content(body, checked), who.userId());
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return repository.find(versionId).orElseThrow(SkillProblems::conflict);
    }

    @Transactional
    Saved update(UUID skillId, Body body) {
        Header header = repository.header(skillId).orElseThrow(SkillProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        CurrentContextHolder.Binding who = binding();
        GradingSkillVersionView latest = repository.latest(skillId).orElseThrow(SkillProblems::notFound);
        if (body.questionTypeCode() != null && !body.questionTypeCode().equals(header.questionTypeCode())) {
            throw SkillProblems.invalid(List.of(new FieldProblem("questionTypeCode", "cannot change: make a new skill")));
        }
        switch (latest.status()) {
            case IN_REVIEW -> throw SkillProblems.underReview();
            case ARCHIVED -> throw SkillProblems.retired();
            case DRAFT, CHANGES_REQUESTED -> {
                if (!Objects.equals(latest.createdBy(), who.userId())) {
                    throw SkillProblems.anotherAuthorsDraft();
                }
            }
            case PUBLISHED -> { }
        }
        SkillValidator.Checked checked = validator.validate(header.examCode(), body.rubric(), body.criteria(), body.promptTemplate(), body.samples(), body.resultSchema());
        if (!checked.errors().isEmpty()) {
            throw SkillProblems.invalid(checked.errors());
        }
        try {
            if (latest.status() == ContentStatus.PUBLISHED) {
                UUID versionId = UUID.randomUUID();
                repository.insertVersion(versionId, header, latest.versionNo() + 1, content(body, checked), who.userId());
                return new Saved(repository.find(versionId).orElseThrow(SkillProblems::conflict), true);
            }
            if (repository.updateDraft(latest.versionId(), content(body, checked)) != 1) {
                throw SkillProblems.conflict();
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return new Saved(repository.find(latest.versionId()).orElseThrow(SkillProblems::conflict), false);
    }

    @Transactional(readOnly = true)
    GradingSkillVersionView get(UUID skillId, Integer versionNo) {
        Header header = repository.header(skillId).orElseThrow(SkillProblems::notFound);
        requireReader(header.examCode());
        return (versionNo == null ? repository.latest(skillId) : repository.versionNo(skillId, versionNo)).orElseThrow(SkillProblems::notFound);
    }

    @Transactional(readOnly = true)
    List<GradingSkillVersionView> versions(UUID skillId) {
        Header header = repository.header(skillId).orElseThrow(SkillProblems::notFound);
        requireReader(header.examCode());
        return repository.versions(skillId);
    }

    @Transactional(readOnly = true)
    Page list(String examCode, ContentStatus status, Integer page) {
        if (examCode != null && !examCode.isBlank()) {
            requireReader(examCode);
        } else if (!authz.has(Role.AUTHOR, Optional.empty()) && !authz.has(Role.REVIEWER, Optional.empty()) && !authz.has(Role.TEACHER, Optional.empty())) {
            throw new AccessDeniedException("not an author, reviewer or teacher");
        }
        int offset = Math.max(0, page == null ? 0 : page) * PAGE;
        SkillRepository.Filter filter = new SkillRepository.Filter(examCode == null || examCode.isBlank() ? null : examCode, status);
        return new Page(repository.list(filter, PAGE, offset), repository.count(filter), offset, PAGE);
    }

    @Transactional
    UUID submit(UUID skillId) {
        Header header = repository.header(skillId).orElseThrow(SkillProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        GradingSkillVersionView latest = repository.latest(skillId).orElseThrow(SkillProblems::notFound);
        if (!latest.status().editable()) {
            throw SkillProblems.notEditable();
        }
        if (!Objects.equals(latest.createdBy(), binding().userId())) {
            throw SkillProblems.anotherAuthorsDraft();
        }
        return reviews.submit(new VersionRef(GradingSkillVersions.REVIEW_TYPE, latest.versionId()));
    }

    /**
     * Runs the latest version of the skill (any status: the author tries a draft before submitting it) on a sample answer and returns the model's answer, validated
     * against the result schema by the AI grader; nothing is saved, no score is kept and no limit is counted. The rows are read in a short read-only transaction; the call
     * to the model happens after it, so no database connection waits for Gemini. Without an AI grader in the application the answer is 501.
     */
    DryRunOutcome dryRun(UUID skillId, DryRun what) {
        boolean text = what != null && what.response() != null && !what.response().isBlank();
        Prepared prepared = readOnly.execute(st -> {
            Header header = repository.header(skillId).orElseThrow(SkillProblems::notFound);
            Optional<String> exam = Optional.of(header.examCode());
            if (!authz.has(Role.AUTHOR, exam) && !authz.has(Role.REVIEWER, exam)) {
                throw new AccessDeniedException("not an author or reviewer of " + header.examCode());
            }
            GradingSkillVersionView latest = repository.latest(skillId).orElseThrow(SkillProblems::notFound);
            boolean recording = what != null && what.mediaId() != null;
            if (text == recording) {
                throw SkillProblems.invalid(List.of(new FieldProblem("response", "give the answer as text (response) or as a recording (mediaId), one of the two")));
            }
            return new Prepared(latest, text ? new SkillDryRunner.Answer.Text(what.response()) : recording(header.examCode(), what.mediaId()));
        });
        SkillDryRunner grader = runner.getIfAvailable();
        if (grader == null) {
            throw SkillProblems.dryRunUnavailable();
        }
        String task = what.task() == null || what.task().isBlank() ? DEFAULT_TASK : what.task();
        try {
            return new DryRunOutcome(prepared.skill(), grader.run(prepared.skill(), task, prepared.answer()));
        } catch (SkillDryRunner.Failure e) {
            throw SkillProblems.dryRunFailed(e.kind(), e.getMessage());
        }
    }

    /** The version that ran and the model's validated answer. */
    record DryRunOutcome(GradingSkillVersionView skill, SkillDryRunner.Result result) {}

    private record Prepared(GradingSkillVersionView skill, SkillDryRunner.Answer answer) {}

    /** A recording of the skill's exam the caller may see (row-level security), read whole: Speaking answers are short. */
    private SkillDryRunner.Answer recording(String examCode, UUID mediaId) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("e9x-dry-run-", ".bin");
            MediaAssets.MediaInfo info = media.copyTo(mediaId, tmp);
            if (info.kind() != MediaKind.AUDIO || !examCode.equals(info.examCode())) {
                throw SkillProblems.invalid(List.of(new FieldProblem("mediaId", "is not a recording of the exam " + examCode)));
            }
            return new SkillDryRunner.Answer.Audio(Files.readAllBytes(tmp), info.mimeType());
        } catch (NoSuchElementException e) {
            throw SkillProblems.invalid(List.of(new FieldProblem("mediaId", "is not a file you can use")));
        } catch (IOException | StagedFilesException e) {
            throw SkillProblems.dryRunFailed(SkillDryRunner.Failure.Kind.TRANSIENT, "the recording could not be read");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a temp file
                }
            }
        }
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------------------

    private static Content content(Body body, SkillValidator.Checked checked) {
        return new Content(body.rubric(), checked.criteria(), body.promptTemplate(), checked.samples(), body.resultSchema());
    }

    /** AUTHOR and REVIEWER read every status; a TEACHER reads the PUBLISHED ones (row-level security decides which rows come back). */
    private void requireReader(String examCode) {
        Optional<String> exam = Optional.of(examCode);
        if (!authz.has(Role.AUTHOR, exam) && !authz.has(Role.REVIEWER, exam) && !authz.has(Role.TEACHER, exam)) {
            throw new AccessDeniedException("not an author, reviewer or teacher of " + examCode);
        }
    }

    private static CurrentContextHolder.Binding binding() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no signed-in user"));
    }

    static RuntimeException translate(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                if ("42501".equals(sql.getSQLState())) {
                    return new AccessDeniedException("not allowed");
                }
                if ("23505".equals(sql.getSQLState()) || "23000".equals(sql.getSQLState())) {
                    return SkillProblems.conflict();
                }
            }
        }
        return e;
    }
}
