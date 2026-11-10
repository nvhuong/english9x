package com.english9x.content.question.internal;

import com.english9x.catalog.api.FieldError;
import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.catalog.api.SchemaValidator;
import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.question.api.QuestionVersionView;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.question.internal.QuestionProblems.FieldProblem;
import com.english9x.content.question.internal.QuestionRepository.Header;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Questions (F09, F18). The rules, in the order they are applied:
 * <ol>
 *   <li>who: an AUTHOR of the question's exam in the current context ({@link Authz}, asked of the database); the row-level policies ask again;
 *   <li>what state: only the latest version decides. DRAFT / CHANGES_REQUESTED of the caller: edited in place. DRAFT / CHANGES_REQUESTED of another
 *       author: refused (409; ledger 141 default, no co-editing, so the self-review check has one author to compare). IN_REVIEW: refused.
 *       PUBLISHED: a NEW draft version (number + 1) is made from the request, the published one is never touched. ARCHIVED: refused;
 *   <li>what content: the schemas of the type for content and answer key (JSON paths), the files they point at, the size, points, explanation, group.
 * </ol>
 * A version is submitted for review through {@link ReviewService} (which audits and notifies); this class only picks the version.
 */
@Service
class QuestionService {

    /** @param points defaults to 1 */
    record Body(String typeCode, UUID groupId, JsonNode content, JsonNode answerKey, String explanation, BigDecimal points) {}

    record Saved(QuestionVersionView view, boolean newVersion) {}

    record Page(List<QuestionRepository.Summary> items, long total, int offset, int limit) {}

    private final Authz authz;
    private final QuestionTypes types;
    private final SchemaValidator schemas;
    private final MediaAssets media;
    private final ReviewService reviews;
    private final QuestionRepository repository;
    private final GroupRepository groups;
    private final QuestionProperties props;
    private final ObjectMapper json;

    QuestionService(Authz authz, QuestionTypes types, SchemaValidator schemas, MediaAssets media, ReviewService reviews, QuestionRepository repository,
                    GroupRepository groups, QuestionProperties props, ObjectMapper json) {
        this.authz = authz;
        this.types = types;
        this.schemas = schemas;
        this.media = media;
        this.reviews = reviews;
        this.repository = repository;
        this.groups = groups;
        this.props = props;
        this.json = json;
    }

    @Transactional
    QuestionVersionView create(Body body) {
        if (body.typeCode() == null || body.typeCode().isBlank()) {
            throw QuestionProblems.invalid(List.of(new FieldProblem("typeCode", "is required")));
        }
        QuestionTypeDef def = types.find(body.typeCode())
                .orElseThrow(() -> QuestionProblems.invalid(List.of(new FieldProblem("typeCode", "is not a question type"))));
        authz.require(Role.AUTHOR, Optional.of(def.examCode()));
        CurrentContextHolder.Binding who = binding();

        List<FieldProblem> errors = validate(def, body, true);
        if (body.groupId() != null) {
            Optional<GroupRepository.Header> g = groups.header(body.groupId());
            if (g.isEmpty() || !g.get().examCode().equals(def.examCode())) {
                errors.add(new FieldProblem("groupId", "is not a group of this exam that you may use"));
            }
        }
        if (!errors.isEmpty()) {
            throw QuestionProblems.invalid(errors);
        }
        Header header = new Header(UUID.randomUUID(), who.orgId().orElse(null), def.examCode(), def.code(), body.groupId(), who.userId());
        UUID versionId = UUID.randomUUID();
        try {
            repository.insertHeader(header);
            repository.insertVersion(versionId, header, 1, body.content(), body.answerKey(), clean(body.explanation()), points(body), who.userId());
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return repository.find(versionId).orElseThrow(QuestionProblems::conflict);
    }

    @Transactional
    Saved update(UUID questionId, Body body) {
        Header header = repository.header(questionId).orElseThrow(QuestionProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        CurrentContextHolder.Binding who = binding();
        QuestionVersionView latest = repository.latest(questionId).orElseThrow(QuestionProblems::notFound);
        if (body.typeCode() != null && !body.typeCode().equals(header.typeCode())) {
            throw QuestionProblems.invalid(List.of(new FieldProblem("typeCode", "cannot change: make a new question")));
        }
        if (body.groupId() != null && !body.groupId().equals(header.groupId())) {
            throw QuestionProblems.invalid(List.of(new FieldProblem("groupId", "cannot change: it is fixed when the question is made")));
        }
        switch (latest.status()) {
            case IN_REVIEW -> throw QuestionProblems.underReview();
            case ARCHIVED -> throw QuestionProblems.retired();
            case DRAFT, CHANGES_REQUESTED -> {
                if (!latest.createdBy().equals(who.userId())) {
                    throw QuestionProblems.anotherAuthorsDraft();
                }
            }
            case PUBLISHED -> { }
        }
        QuestionTypeDef def = types.get(header.typeCode());
        List<FieldProblem> errors = validate(def, body, false);
        if (!errors.isEmpty()) {
            throw QuestionProblems.invalid(errors);
        }
        try {
            if (latest.status() == ContentStatus.PUBLISHED) {
                UUID versionId = UUID.randomUUID();
                repository.insertVersion(versionId, header, latest.versionNo() + 1, body.content(), body.answerKey(), clean(body.explanation()), points(body), who.userId());
                return new Saved(repository.find(versionId).orElseThrow(QuestionProblems::conflict), true);
            }
            if (repository.updateDraft(latest.versionId(), body.content(), body.answerKey(), clean(body.explanation()), points(body)) != 1) {
                throw QuestionProblems.conflict();
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return new Saved(repository.find(latest.versionId()).orElseThrow(QuestionProblems::conflict), false);
    }

    /** The latest version of the question, or the numbered one; the key is included, so the caller must be an AUTHOR or REVIEWER of the exam. */
    @Transactional(readOnly = true)
    QuestionVersionView get(UUID questionId, Integer versionNo) {
        Header header = repository.header(questionId).orElseThrow(QuestionProblems::notFound);
        requireStaff(header.examCode());
        Optional<QuestionVersionView> v = versionNo == null ? repository.latest(questionId) : repository.versionNo(questionId, versionNo);
        return v.orElseThrow(QuestionProblems::notFound);
    }

    @Transactional(readOnly = true)
    List<QuestionVersionView> versions(UUID questionId) {
        Header header = repository.header(questionId).orElseThrow(QuestionProblems::notFound);
        requireStaff(header.examCode());
        return repository.versions(questionId);
    }

    @Transactional(readOnly = true)
    Page list(String examCode, String typeCode, ContentStatus status, UUID groupId, Integer page, Integer size) {
        if (examCode != null && !examCode.isBlank()) {
            requireStaff(examCode);
        } else if (!authz.has(Role.AUTHOR, Optional.empty()) && !authz.has(Role.REVIEWER, Optional.empty())) {
            throw new AccessDeniedException("not an author or reviewer");
        }
        int limit = props.pageSize(size);
        int offset = Math.max(0, page == null ? 0 : page) * limit;
        QuestionRepository.Filter filter = new QuestionRepository.Filter(blankToNull(examCode), blankToNull(typeCode), status, groupId);
        return new Page(repository.list(filter, limit, offset), repository.count(filter), offset, limit);
    }

    /** Submits the latest version (a DRAFT or CHANGES_REQUESTED of the caller) for review; the workflow audits it and notifies the reviewers. */
    @Transactional
    UUID submit(UUID questionId) {
        Header header = repository.header(questionId).orElseThrow(QuestionProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        QuestionVersionView latest = repository.latest(questionId).orElseThrow(QuestionProblems::notFound);
        if (!latest.status().editable()) {
            throw QuestionProblems.notEditable();
        }
        if (!latest.createdBy().equals(binding().userId())) {
            throw QuestionProblems.anotherAuthorsDraft();
        }
        return reviews.submit(new VersionRef(QuestionVersions.REVIEW_TYPE, latest.versionId()));
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------------------

    private List<FieldProblem> validate(QuestionTypeDef def, Body body, boolean creating) {
        List<FieldProblem> errors = new ArrayList<>();
        ContentChecks.size(json, "content", body.content(), props.maxJsonSize().toBytes(), errors);
        ContentChecks.size(json, "answerKey", body.answerKey(), props.maxJsonSize().toBytes(), errors);
        if (errors.isEmpty()) {
            for (FieldError e : schemas.validate(def.code(), body.content(), body.answerKey())) {
                errors.add(new FieldProblem(e.path(), e.message()));
            }
        }
        if (errors.isEmpty()) {
            ContentChecks.media(media, def.examCode(), "content", body.content(), errors);
            ContentChecks.media(media, def.examCode(), "answerKey", body.answerKey(), errors);
        }
        if (body.points() != null && (body.points().signum() <= 0 || body.points().compareTo(BigDecimal.valueOf(1000)) > 0 || body.points().scale() > 2)) {
            errors.add(new FieldProblem("points", "must be above 0 and at most 1000, with at most two decimals"));
        }
        if (body.explanation() != null && body.explanation().length() > 20000) {
            errors.add(new FieldProblem("explanation", "is too long"));
        }
        return errors;
    }

    private static BigDecimal points(Body b) {
        return b.points() == null ? BigDecimal.ONE : b.points();
    }

    private static String clean(String explanation) {
        return explanation == null || explanation.isBlank() ? null : explanation;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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

    /** A race for the same open version is a conflict the author can retry; a policy refusal is a 403, whatever the cause. */
    static RuntimeException translate(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                if ("42501".equals(sql.getSQLState())) {
                    return new AccessDeniedException("not allowed");
                }
                if ("23505".equals(sql.getSQLState()) || "23000".equals(sql.getSQLState())) {
                    return QuestionProblems.conflict();
                }
            }
        }
        return e;
    }
}
