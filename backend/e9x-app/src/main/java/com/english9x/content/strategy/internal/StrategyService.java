package com.english9x.content.strategy.internal;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.content.strategy.api.StrategyLessonVersionView;
import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.content.strategy.internal.StrategyProblems.FieldProblem;
import com.english9x.content.strategy.internal.StrategyRepository.Header;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strategy lessons (F14). Same rules as questions, in the same order: who (an AUTHOR of the lesson's exam in the current context; the policies ask again),
 * what state (only the latest version decides: own DRAFT / CHANGES_REQUESTED edited in place, another author's refused, IN_REVIEW and ARCHIVED refused,
 * PUBLISHED gives a NEW draft version), what content (title, the question types (>= 1, of this exam), the blocks: {@link BlockValidator}).
 */
@Service
class StrategyService {

    /** @param examCode used when the lesson is made; ignored (and may be null) on update, the exam never changes */
    record Body(String examCode, String title, JsonNode blocks, List<String> questionTypeCodes) {}

    record Saved(StrategyLessonVersionView view, boolean newVersion) {}

    record Page(List<StrategyRepository.Summary> items, long total, int offset, int limit) {}

    private static final int PAGE = 50;

    private final Authz authz;
    private final QuestionTypes types;
    private final ReviewService reviews;
    private final StrategyRepository repository;
    private final BlockValidator blocks;

    StrategyService(Authz authz, QuestionTypes types, ReviewService reviews, StrategyRepository repository, BlockValidator blocks) {
        this.authz = authz;
        this.types = types;
        this.reviews = reviews;
        this.repository = repository;
        this.blocks = blocks;
    }

    @Transactional
    StrategyLessonVersionView create(Body body) {
        if (body.examCode() == null || body.examCode().isBlank()) {
            throw StrategyProblems.invalid(List.of(new FieldProblem("examCode", "is required")));
        }
        authz.require(Role.AUTHOR, Optional.of(body.examCode()));
        CurrentContextHolder.Binding who = binding();
        List<FieldProblem> errors = validate(body.examCode(), body);
        if (!errors.isEmpty()) {
            throw StrategyProblems.invalid(errors);
        }
        Header header = new Header(UUID.randomUUID(), who.orgId().orElse(null), body.examCode(), who.userId());
        UUID versionId = UUID.randomUUID();
        try {
            repository.insertHeader(header);
            repository.insertVersion(versionId, header, 1, body.title().trim(), body.blocks(), distinct(body.questionTypeCodes()), who.userId());
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return repository.find(versionId).orElseThrow(StrategyProblems::conflict);
    }

    @Transactional
    Saved update(UUID lessonId, Body body) {
        Header header = repository.header(lessonId).orElseThrow(StrategyProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        CurrentContextHolder.Binding who = binding();
        StrategyLessonVersionView latest = repository.latest(lessonId).orElseThrow(StrategyProblems::notFound);
        if (body.examCode() != null && !body.examCode().equals(header.examCode())) {
            throw StrategyProblems.invalid(List.of(new FieldProblem("examCode", "cannot change: make a new lesson")));
        }
        switch (latest.status()) {
            case IN_REVIEW -> throw StrategyProblems.underReview();
            case ARCHIVED -> throw StrategyProblems.retired();
            case DRAFT, CHANGES_REQUESTED -> {
                if (!latest.createdBy().equals(who.userId())) {
                    throw StrategyProblems.anotherAuthorsDraft();
                }
            }
            case PUBLISHED -> { }
        }
        List<FieldProblem> errors = validate(header.examCode(), body);
        if (!errors.isEmpty()) {
            throw StrategyProblems.invalid(errors);
        }
        try {
            if (latest.status() == ContentStatus.PUBLISHED) {
                UUID versionId = UUID.randomUUID();
                repository.insertVersion(versionId, header, latest.versionNo() + 1, body.title().trim(), body.blocks(), distinct(body.questionTypeCodes()), who.userId());
                return new Saved(repository.find(versionId).orElseThrow(StrategyProblems::conflict), true);
            }
            if (repository.updateDraft(latest.versionId(), header, body.title().trim(), body.blocks(), distinct(body.questionTypeCodes())) != 1) {
                throw StrategyProblems.conflict();
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return new Saved(repository.find(latest.versionId()).orElseThrow(StrategyProblems::conflict), false);
    }

    @Transactional(readOnly = true)
    StrategyLessonVersionView get(UUID lessonId, Integer versionNo) {
        Header header = repository.header(lessonId).orElseThrow(StrategyProblems::notFound);
        requireStaff(header.examCode());
        return (versionNo == null ? repository.latest(lessonId) : repository.versionNo(lessonId, versionNo)).orElseThrow(StrategyProblems::notFound);
    }

    @Transactional(readOnly = true)
    List<StrategyLessonVersionView> versions(UUID lessonId) {
        Header header = repository.header(lessonId).orElseThrow(StrategyProblems::notFound);
        requireStaff(header.examCode());
        return repository.versions(lessonId);
    }

    @Transactional(readOnly = true)
    Page list(String examCode, ContentStatus status, Integer page) {
        if (examCode != null && !examCode.isBlank()) {
            requireStaff(examCode);
        } else if (!authz.has(Role.AUTHOR, Optional.empty()) && !authz.has(Role.REVIEWER, Optional.empty())) {
            throw new AccessDeniedException("not an author or reviewer");
        }
        int offset = Math.max(0, page == null ? 0 : page) * PAGE;
        StrategyRepository.Filter filter = new StrategyRepository.Filter(examCode == null || examCode.isBlank() ? null : examCode, status);
        return new Page(repository.list(filter, PAGE, offset), repository.count(filter), offset, PAGE);
    }

    @Transactional
    UUID submit(UUID lessonId) {
        Header header = repository.header(lessonId).orElseThrow(StrategyProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        StrategyLessonVersionView latest = repository.latest(lessonId).orElseThrow(StrategyProblems::notFound);
        if (!latest.status().editable()) {
            throw StrategyProblems.notEditable();
        }
        if (!latest.createdBy().equals(binding().userId())) {
            throw StrategyProblems.anotherAuthorsDraft();
        }
        return reviews.submit(new VersionRef(StrategyLessonVersions.REVIEW_TYPE, latest.versionId()));
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------------------

    private List<FieldProblem> validate(String examCode, Body body) {
        List<FieldProblem> errors = new ArrayList<>();
        if (body.title() == null || body.title().isBlank()) {
            errors.add(new FieldProblem("title", "is required"));
        } else if (body.title().trim().length() > 200) {
            errors.add(new FieldProblem("title", "is too long"));
        }
        List<String> codes = body.questionTypeCodes();
        if (codes == null || codes.isEmpty()) {
            errors.add(new FieldProblem("questionTypeCodes", "needs at least one question type the lesson teaches"));
        } else {
            for (int i = 0; i < codes.size(); i++) {
                Optional<QuestionTypeDef> def = codes.get(i) == null ? Optional.empty() : types.find(codes.get(i));
                if (def.isEmpty() || !def.get().examCode().equals(examCode)) {
                    errors.add(new FieldProblem("questionTypeCodes[" + i + "]", "is not a question type of this exam"));
                }
            }
        }
        errors.addAll(blocks.validate(examCode, body.blocks()));
        return errors;
    }

    private static List<String> distinct(List<String> codes) {
        return List.copyOf(new LinkedHashSet<>(codes));
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
                    return StrategyProblems.conflict();
                }
            }
        }
        return e;
    }
}
