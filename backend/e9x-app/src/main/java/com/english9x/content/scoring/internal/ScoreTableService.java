package com.english9x.content.scoring.internal;

import com.english9x.catalog.api.ExamInfo;
import com.english9x.catalog.api.Exams;
import com.english9x.content.scoring.api.ScoreRow;
import com.english9x.content.scoring.api.ScoreScope;
import com.english9x.content.scoring.api.ScoreTableVersionView;
import com.english9x.content.scoring.api.ScoreTableVersions;
import com.english9x.content.scoring.internal.RowsValidator.Result;
import com.english9x.content.scoring.internal.ScoreProblems.FieldProblem;
import com.english9x.content.scoring.internal.ScoreTableRepository.Header;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Score tables (F15), the conversion of raw to scaled scores of an exam. Same rules as questions and lessons, in the same order: who (an AUTHOR of the
 * table's exam; the policies ask again), what state (only the latest version decides: own DRAFT / CHANGES_REQUESTED edited in place, another author's
 * refused, IN_REVIEW and ARCHIVED refused, PUBLISHED gives a NEW draft version), what content ({@link RowsValidator}: the domain's TableConverter).
 *
 * <p>Only the EXAM scope exists (the default of an exam, English9x's). TEST scope is refused with 409 until P2.6 creates tests, and the database
 * refuses the row too.
 */
@Service
class ScoreTableService {

    /** @param examCode, skill used when the table is made; on update they may be null, and when given must match (they never change) */
    record Body(String examCode, String skill, String scope, UUID testId, JsonNode rows) {}

    record Saved(ScoreTableVersionView view, boolean newVersion) {}

    record Page(List<ScoreTableRepository.Summary> items, long total, int offset, int limit) {}

    private static final int PAGE = 50;

    private final Authz authz;
    private final Exams exams;
    private final ReviewService reviews;
    private final ScoreTableRepository repository;

    ScoreTableService(Authz authz, Exams exams, ReviewService reviews, ScoreTableRepository repository) {
        this.authz = authz;
        this.exams = exams;
        this.reviews = reviews;
        this.repository = repository;
    }

    @Transactional
    ScoreTableVersionView create(Body body) {
        if (body.examCode() == null || body.examCode().isBlank()) {
            throw ScoreProblems.invalid(List.of(new FieldProblem("examCode", "is required")));
        }
        authz.require(Role.AUTHOR, Optional.of(body.examCode()));
        CurrentContextHolder.Binding who = binding();
        requireExamScope(body.scope());
        List<FieldProblem> errors = new ArrayList<>();
        if (body.testId() != null) {
            errors.add(new FieldProblem("testId", "belongs to scope TEST, which is not available yet; the default of the exam has no test"));
        }
        Optional<ExamInfo> exam = exams.find(body.examCode());
        if (exam.isEmpty()) {
            errors.add(new FieldProblem("examCode", "is not an exam of the catalog"));
        } else if (body.skill() == null || !exam.get().scoreSkills().contains(body.skill())) {
            errors.add(new FieldProblem("skill", "is not a score of this exam (it keeps " + exam.get().scoreSkills() + ")"));
        }
        Result rows = RowsValidator.validate(body.rows());
        errors.addAll(rows.errors());
        if (!errors.isEmpty()) {
            throw ScoreProblems.invalid(errors);
        }
        // the exam default is English9x's: an organisation's context cannot hold it (the database's constraint says the same)
        if (who.orgId().isPresent()) {
            throw new AccessDeniedException("the conversion table of an exam is English9x's");
        }
        Optional<Header> existing = repository.examDefault(body.examCode(), body.skill(), null);
        if (existing.isPresent()) {
            throw ScoreProblems.exists(existing.get().id());
        }
        Header header = new Header(UUID.randomUUID(), null, body.examCode(), body.skill(), ScoreScope.EXAM, null, "TABLE");
        UUID versionId = UUID.randomUUID();
        try {
            repository.insertHeader(header);
            repository.insertVersion(versionId, header, 1, rows.rows(), who.userId());
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return repository.find(versionId).orElseThrow(ScoreProblems::conflict);
    }

    @Transactional
    Saved update(UUID tableId, Body body) {
        Header header = repository.header(tableId).orElseThrow(ScoreProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        CurrentContextHolder.Binding who = binding();
        requireExamScope(body.scope());
        ScoreTableVersionView latest = repository.latest(tableId).orElseThrow(ScoreProblems::notFound);
        List<FieldProblem> errors = new ArrayList<>();
        if (body.testId() != null) {
            errors.add(new FieldProblem("testId", "belongs to scope TEST, which is not available yet; the default of the exam has no test"));
        }
        if (body.examCode() != null && !body.examCode().equals(header.examCode())) {
            errors.add(new FieldProblem("examCode", "cannot change: make a new table"));
        }
        if (body.skill() != null && !body.skill().equals(header.skill())) {
            errors.add(new FieldProblem("skill", "cannot change: make a new table"));
        }
        if (!"TABLE".equals(header.function())) {
            errors.add(new FieldProblem("skill", "is computed by " + header.function() + ", it has no table to edit"));
        }
        if (!errors.isEmpty()) {
            throw ScoreProblems.invalid(errors);
        }
        switch (latest.status()) {
            case IN_REVIEW -> throw ScoreProblems.underReview();
            case ARCHIVED -> throw ScoreProblems.retired();
            case DRAFT, CHANGES_REQUESTED -> {
                if (!Objects.equals(latest.createdBy(), who.userId())) {
                    throw ScoreProblems.anotherAuthorsDraft();
                }
            }
            case PUBLISHED -> { }
        }
        Result rows = RowsValidator.validate(body.rows());
        if (!rows.errors().isEmpty()) {
            throw ScoreProblems.invalid(rows.errors());
        }
        try {
            if (latest.status() == ContentStatus.PUBLISHED) {
                UUID versionId = UUID.randomUUID();
                repository.insertVersion(versionId, header, latest.versionNo() + 1, rows.rows(), who.userId());
                return new Saved(repository.find(versionId).orElseThrow(ScoreProblems::conflict), true);
            }
            if (repository.updateDraft(latest.versionId(), rows.rows()) != 1) {
                throw ScoreProblems.conflict();
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return new Saved(repository.find(latest.versionId()).orElseThrow(ScoreProblems::conflict), false);
    }

    @Transactional(readOnly = true)
    ScoreTableVersionView get(UUID tableId, Integer versionNo) {
        Header header = repository.header(tableId).orElseThrow(ScoreProblems::notFound);
        requireStaff(header.examCode());
        return (versionNo == null ? repository.latest(tableId) : repository.versionNo(tableId, versionNo)).orElseThrow(ScoreProblems::notFound);
    }

    @Transactional(readOnly = true)
    List<ScoreTableVersionView> versions(UUID tableId) {
        Header header = repository.header(tableId).orElseThrow(ScoreProblems::notFound);
        requireStaff(header.examCode());
        return repository.versions(tableId);
    }

    @Transactional(readOnly = true)
    Page list(String examCode, ContentStatus status, Integer page) {
        if (examCode != null && !examCode.isBlank()) {
            requireStaff(examCode);
        } else if (!authz.has(Role.AUTHOR, Optional.empty()) && !authz.has(Role.REVIEWER, Optional.empty())) {
            throw new AccessDeniedException("not an author or reviewer");
        }
        int offset = Math.max(0, page == null ? 0 : page) * PAGE;
        ScoreTableRepository.Filter filter = new ScoreTableRepository.Filter(examCode == null || examCode.isBlank() ? null : examCode, status);
        return new Page(repository.list(filter, PAGE, offset), repository.count(filter), offset, PAGE);
    }

    @Transactional
    UUID submit(UUID tableId) {
        Header header = repository.header(tableId).orElseThrow(ScoreProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        ScoreTableVersionView latest = repository.latest(tableId).orElseThrow(ScoreProblems::notFound);
        if (!latest.status().editable()) {
            throw ScoreProblems.notEditable();
        }
        if (!Objects.equals(latest.createdBy(), binding().userId())) {
            throw ScoreProblems.anotherAuthorsDraft();
        }
        return reviews.submit(new VersionRef(ScoreTableVersions.REVIEW_TYPE, latest.versionId()));
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------------------

    /** EXAM is the only scope until P2.6; anything else that names a scope is refused (TEST: 409, it exists in the design but cannot be stored). */
    private static void requireExamScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw ScoreProblems.invalid(List.of(new FieldProblem("scope", "is required: EXAM")));
        }
        if ("TEST".equals(scope)) {
            throw ScoreProblems.testScopeUnavailable();
        }
        if (!"EXAM".equals(scope)) {
            throw ScoreProblems.invalid(List.of(new FieldProblem("scope", "must be EXAM or TEST")));
        }
    }

    private void requireStaff(String examCode) {
        Optional<String> exam = Optional.of(examCode);
        if (!authz.has(Role.AUTHOR, exam) && !authz.has(Role.REVIEWER, exam)) {
            throw new AccessDeniedException("not an author or reviewer of " + examCode);
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
                    return ScoreProblems.conflict();
                }
            }
        }
        return e;
    }
}
