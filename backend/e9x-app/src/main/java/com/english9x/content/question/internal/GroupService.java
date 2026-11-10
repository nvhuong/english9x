package com.english9x.content.question.internal;

import com.english9x.catalog.api.Exams;
import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.question.api.QuestionGroupVersionView;
import com.english9x.content.question.api.QuestionGroupVersions;
import com.english9x.content.question.api.QuestionVersionView;
import com.english9x.content.question.internal.QuestionProblems.FieldProblem;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Question groups: the shared stimulus of several questions (a passage, a recording, a picture) and the ordered list of the question versions asked
 * about it. Same rules as {@link QuestionService}: an AUTHOR of the exam; the latest version decides (own draft edited in place, another author's draft
 * refused, under review refused, PUBLISHED gives a new draft version); submitted through {@link ReviewService}.
 *
 * <p>The members are question VERSIONS, and they are the group version's dependencies in the workflow: it cannot be approved while one is not PUBLISHED.
 * A member must be a question of the same exam and owner that the author can read. The members of a group version are frozen with it (child guard).
 */
@Service
class GroupService {

    record Body(String examCode, JsonNode content, JsonNode solution, List<UUID> questionVersionIds) {}

    record Saved(QuestionGroupVersionView view, boolean newVersion) {}

    record Page(List<GroupRepository.Summary> items, int offset, int limit) {}

    /** What a group's content may hold (the learner sees all of it) and what its solution side may hold (the learner sees none of it). */
    private static final Map<String, Integer> CONTENT_TEXTS = Map.of("title", 200, "instructions", 4000, "passage", 100_000);
    private static final Set<String> CONTENT_MEDIA = Set.of("audioMediaId", "imageMediaId");
    private static final Map<String, Integer> SOLUTION_TEXTS = Map.of("transcript", 50_000, "notes", 20_000);

    private final Authz authz;
    private final Exams exams;
    private final MediaAssets media;
    private final ReviewService reviews;
    private final GroupRepository repository;
    private final QuestionRepository questions;
    private final QuestionProperties props;
    private final ObjectMapper json;

    GroupService(Authz authz, Exams exams, MediaAssets media, ReviewService reviews, GroupRepository repository, QuestionRepository questions,
                 QuestionProperties props, ObjectMapper json) {
        this.authz = authz;
        this.exams = exams;
        this.media = media;
        this.reviews = reviews;
        this.repository = repository;
        this.questions = questions;
        this.props = props;
        this.json = json;
    }

    @Transactional
    QuestionGroupVersionView create(Body body) {
        if (body.examCode() == null || body.examCode().isBlank()) {
            throw QuestionProblems.groupInvalid(List.of(new FieldProblem("examCode", "is required")));
        }
        if (!exams.exists(body.examCode())) {
            throw QuestionProblems.groupInvalid(List.of(new FieldProblem("examCode", "is not an exam")));
        }
        authz.require(Role.AUTHOR, Optional.of(body.examCode()));
        CurrentContextHolder.Binding who = binding();
        List<FieldProblem> errors = validate(body.examCode(), body, who.orgId());
        if (!errors.isEmpty()) {
            throw QuestionProblems.groupInvalid(errors);
        }
        GroupRepository.Header header = new GroupRepository.Header(UUID.randomUUID(), who.orgId().orElse(null), body.examCode(), who.userId());
        UUID versionId = UUID.randomUUID();
        try {
            repository.insertHeader(header);
            repository.insertVersion(versionId, header, 1, body.content(), solution(body), members(body), who.userId());
        } catch (DataAccessException e) {
            throw QuestionService.translate(e);
        }
        return repository.find(versionId).orElseThrow(QuestionProblems::conflict);
    }

    @Transactional
    Saved update(UUID groupId, Body body) {
        GroupRepository.Header header = repository.header(groupId).orElseThrow(QuestionProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        CurrentContextHolder.Binding who = binding();
        QuestionGroupVersionView latest = repository.latest(groupId).orElseThrow(QuestionProblems::notFound);
        if (body.examCode() != null && !body.examCode().equals(header.examCode())) {
            throw QuestionProblems.groupInvalid(List.of(new FieldProblem("examCode", "cannot change")));
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
        List<FieldProblem> errors = validate(header.examCode(), body, who.orgId());
        if (!errors.isEmpty()) {
            throw QuestionProblems.groupInvalid(errors);
        }
        try {
            if (latest.status() == ContentStatus.PUBLISHED) {
                UUID versionId = UUID.randomUUID();
                repository.insertVersion(versionId, header, latest.versionNo() + 1, body.content(), solution(body), members(body), who.userId());
                return new Saved(repository.find(versionId).orElseThrow(QuestionProblems::conflict), true);
            }
            if (repository.updateDraft(latest.versionId(), header, body.content(), solution(body), members(body)) != 1) {
                throw QuestionProblems.conflict();
            }
        } catch (DataAccessException e) {
            throw QuestionService.translate(e);
        }
        return new Saved(repository.find(latest.versionId()).orElseThrow(QuestionProblems::conflict), false);
    }

    @Transactional(readOnly = true)
    QuestionGroupVersionView get(UUID groupId, Integer versionNo) {
        GroupRepository.Header header = repository.header(groupId).orElseThrow(QuestionProblems::notFound);
        requireStaff(header.examCode());
        return (versionNo == null ? repository.latest(groupId) : repository.versionNo(groupId, versionNo)).orElseThrow(QuestionProblems::notFound);
    }

    @Transactional(readOnly = true)
    List<QuestionGroupVersionView> versions(UUID groupId) {
        GroupRepository.Header header = repository.header(groupId).orElseThrow(QuestionProblems::notFound);
        requireStaff(header.examCode());
        return repository.versions(groupId);
    }

    @Transactional(readOnly = true)
    Page list(String examCode, ContentStatus status, Integer page, Integer size) {
        if (examCode != null && !examCode.isBlank()) {
            requireStaff(examCode);
        } else if (!authz.has(Role.AUTHOR, Optional.empty()) && !authz.has(Role.REVIEWER, Optional.empty())) {
            throw new AccessDeniedException("not an author or reviewer");
        }
        int limit = props.pageSize(size);
        int offset = Math.max(0, page == null ? 0 : page) * limit;
        return new Page(repository.list(examCode == null || examCode.isBlank() ? null : examCode, status, limit, offset), offset, limit);
    }

    @Transactional
    UUID submit(UUID groupId) {
        GroupRepository.Header header = repository.header(groupId).orElseThrow(QuestionProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        QuestionGroupVersionView latest = repository.latest(groupId).orElseThrow(QuestionProblems::notFound);
        if (!latest.status().editable()) {
            throw QuestionProblems.notEditable();
        }
        if (!latest.createdBy().equals(binding().userId())) {
            throw QuestionProblems.anotherAuthorsDraft();
        }
        return reviews.submit(new VersionRef(QuestionGroupVersions.REVIEW_TYPE, latest.versionId()));
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------------------

    private List<FieldProblem> validate(String examCode, Body body, Optional<UUID> owner) {
        List<FieldProblem> errors = new ArrayList<>();
        shape("content", body.content(), CONTENT_TEXTS, CONTENT_MEDIA, true, errors);
        if (body.solution() != null && !body.solution().isNull()) {
            shape("solution", body.solution(), SOLUTION_TEXTS, Set.of(), false, errors);
        }
        List<UUID> ids = body.questionVersionIds();
        if (ids == null || ids.isEmpty()) {
            errors.add(new FieldProblem("questionVersionIds", "needs at least one question"));
        } else if (ids.size() > props.maxGroupMembers()) {
            errors.add(new FieldProblem("questionVersionIds", "has more than " + props.maxGroupMembers() + " questions"));
        } else {
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < ids.size(); i++) {
                UUID id = ids.get(i);
                Optional<QuestionVersionView> q = id == null ? Optional.empty() : questions.find(id);
                if (q.isEmpty() || !q.get().examCode().equals(examCode) || !q.get().ownerOrgId().equals(owner)) {
                    errors.add(new FieldProblem("questionVersionIds[" + i + "]", "is not a question of this exam that you may use"));
                } else if (!seen.add(id)) {
                    errors.add(new FieldProblem("questionVersionIds[" + i + "]", "is listed twice"));
                }
            }
        }
        if (errors.isEmpty()) {
            ContentChecks.media(media, examCode, "content", body.content(), errors);
        }
        return errors;
    }

    /** An object with only the allowed keys: text fields within their length, media ids as UUIDs. {@code titleRequired}: the content needs a title. */
    private void shape(String root, JsonNode node, Map<String, Integer> texts, Set<String> mediaKeys, boolean titleRequired, List<FieldProblem> errors) {
        if (node == null || node.isNull()) {
            errors.add(new FieldProblem(root, "is required"));
            return;
        }
        if (!node.isObject()) {
            errors.add(new FieldProblem(root, "must be an object"));
            return;
        }
        ContentChecks.size(json, root, node, props.maxJsonSize().toBytes(), errors);
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            String path = root + "." + e.getKey();
            if (texts.containsKey(e.getKey())) {
                if (!e.getValue().isTextual() || e.getValue().asText().isBlank()) {
                    errors.add(new FieldProblem(path, "must be a non-empty text"));
                } else if (e.getValue().asText().length() > texts.get(e.getKey())) {
                    errors.add(new FieldProblem(path, "is longer than " + texts.get(e.getKey()) + " characters"));
                }
            } else if (mediaKeys.contains(e.getKey())) {
                if (!e.getValue().isTextual() || !e.getValue().asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                    errors.add(new FieldProblem(path, "is not a file id"));
                }
            } else {
                errors.add(new FieldProblem(path, "is not a field of a group"));
            }
        }
        if (titleRequired && !node.has("title")) {
            errors.add(new FieldProblem(root + ".title", "is required"));
        }
    }

    private static JsonNode solution(Body b) {
        return b.solution() == null || b.solution().isNull() ? JsonNodeFactory.instance.objectNode() : b.solution();
    }

    private static List<UUID> members(Body b) {
        return b.questionVersionIds();
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
}
