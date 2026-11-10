package com.english9x.content.test.internal;

import com.english9x.catalog.api.ExamInfo;
import com.english9x.catalog.api.ExamSection;
import com.english9x.catalog.api.Exams;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.content.question.api.QuestionGroupVersionView;
import com.english9x.content.question.api.QuestionGroupVersions;
import com.english9x.content.question.api.QuestionVersionView;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.test.api.TestStaffView;
import com.english9x.content.test.api.TestVersions;
import com.english9x.content.test.internal.TestProblems.FieldProblem;
import com.english9x.content.test.internal.TestRepository.Header;
import com.english9x.content.test.internal.TestRepository.PartRow;
import com.english9x.content.test.internal.TestStructureValidator.Report;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests (F13): the builder of exams from PUBLISHED question groups. Same rules as questions and groups, in the same order: who (an AUTHOR of the exam; the
 * policies ask again), what state (only the latest version decides: own DRAFT / CHANGES_REQUESTED edited in place, another author's refused, IN_REVIEW and
 * ARCHIVED refused, PUBLISHED gives a NEW draft version), what content (the structure: sections of the exam, groups of the test's own owner, questions of
 * the type of their section), then what the exam expects ({@link TestStructureValidator}: warnings on every read, a blocker on submit).
 *
 * <p>The group versions of a test are its dependencies in the workflow: the review cannot approve the test while one of them is not PUBLISHED. They may be
 * drafts while the test is being built (that is how a test and its groups are written side by side); they must be PUBLISHED when the test is.
 *
 * <p>The conversion tables of TEST scope are keyed by the test (not the version), written by the administrator of the test's owner, and listed here as
 * {@code scoreTableOverrides}: the request may name them (each must exist for this test and skill), the response always lists them.
 */
@Service
class TestService {

    /** @param variant IELTS: AC or GT; an exam with no variants: null */
    record CreateBody(String examCode, String variant, String title) {}

    record PartBody(String sectionCode, Integer durationSec, List<UUID> groups) {}

    record OverrideBody(String skill, UUID scoreTableId) {}

    /** @param title null keeps the title; @param maxResumes null = the exam's default */
    record StructureBody(String title, List<PartBody> parts, Integer maxResumes, List<OverrideBody> scoreTableOverrides) {}

    record GroupRef(UUID groupVersionId, UUID groupId, String title, int questions) {}

    record PartDetail(int position, String sectionCode, String skill, int durationSec, List<GroupRef> groups, int questions) {}

    /** A version as the CMS shows it: the structure with the groups named, what the exam expects of it, and the overrides of the test. */
    record Detail(TestStaffView view, List<PartDetail> parts, Report report, List<TestRepository.Override> overrides) {}

    record Saved(Detail detail, boolean newVersion) {}

    record Page(List<TestRepository.Summary> items, int offset, int limit) {}

    private static final int MAX_DURATION_SEC = 86_400;
    private static final int MAX_RESUMES = 20;

    private final Authz authz;
    private final Exams exams;
    private final QuestionTypes types;
    private final QuestionGroupVersions groups;
    private final QuestionVersions questions;
    private final ReviewService reviews;
    private final TestRepository repository;
    private final TestProperties props;

    TestService(Authz authz, Exams exams, QuestionTypes types, QuestionGroupVersions groups, QuestionVersions questions, ReviewService reviews,
                TestRepository repository, TestProperties props) {
        this.authz = authz;
        this.exams = exams;
        this.types = types;
        this.groups = groups;
        this.questions = questions;
        this.reviews = reviews;
        this.repository = repository;
        this.props = props;
    }

    @Transactional
    Detail create(CreateBody body) {
        List<FieldProblem> errors = new ArrayList<>();
        Optional<ExamInfo> exam = body.examCode() == null || body.examCode().isBlank() ? Optional.empty() : exams.find(body.examCode());
        if (body.examCode() == null || body.examCode().isBlank()) {
            errors.add(new FieldProblem("examCode", "is required"));
        } else if (exam.isEmpty()) {
            errors.add(new FieldProblem("examCode", "is not an exam"));
        }
        if (!errors.isEmpty()) {
            throw TestProblems.invalid(errors);
        }
        authz.require(Role.AUTHOR, Optional.of(body.examCode()));
        CurrentContextHolder.Binding who = binding();
        title(body.title(), true, errors);
        List<String> variants = exam.get().variants();
        if (variants.isEmpty() && body.variant() != null && !body.variant().isBlank()) {
            errors.add(new FieldProblem("variant", body.examCode() + " has no variants"));
        } else if (!variants.isEmpty() && (body.variant() == null || !variants.contains(body.variant()))) {
            errors.add(new FieldProblem("variant", "is required: one of " + variants));
        }
        if (!errors.isEmpty()) {
            throw TestProblems.invalid(errors);
        }
        Header header = new Header(UUID.randomUUID(), who.orgId().orElse(null), body.examCode(), variants.isEmpty() ? null : body.variant(), who.userId());
        UUID versionId = UUID.randomUUID();
        try {
            repository.insertHeader(header);
            repository.insertVersion(versionId, header, 1, body.title().strip(), null, List.of(), who.userId());
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return detail(repository.find(versionId).orElseThrow(TestProblems::conflict));
    }

    @Transactional
    Saved update(UUID testId, StructureBody body) {
        Header header = repository.header(testId).orElseThrow(TestProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        CurrentContextHolder.Binding who = binding();
        TestStaffView latest = repository.latest(testId).orElseThrow(TestProblems::notFound);
        switch (latest.status()) {
            case IN_REVIEW -> throw TestProblems.underReview();
            case ARCHIVED -> throw TestProblems.retired();
            case DRAFT, CHANGES_REQUESTED -> {
                if (!latest.createdBy().equals(who.userId())) {
                    throw TestProblems.anotherAuthorsDraft();
                }
            }
            case PUBLISHED -> { }
        }
        List<FieldProblem> errors = new ArrayList<>();
        List<PartRow> parts = structure(header, body, errors);
        String title = latest.title();
        if (body.title() != null) {
            title(body.title(), false, errors);
            title = body.title().strip();
        }
        if (body.maxResumes() != null && (body.maxResumes() < 0 || body.maxResumes() > MAX_RESUMES)) {
            errors.add(new FieldProblem("maxResumes", "must be between 0 and " + MAX_RESUMES));
        }
        overrides(header, body.scoreTableOverrides(), errors);
        if (!errors.isEmpty()) {
            throw TestProblems.invalid(errors);
        }
        try {
            if (latest.status() == ContentStatus.PUBLISHED) {
                UUID versionId = UUID.randomUUID();
                repository.insertVersion(versionId, header, latest.versionNo() + 1, title, body.maxResumes(), parts, who.userId());
                return new Saved(detail(repository.find(versionId).orElseThrow(TestProblems::conflict)), true);
            }
            if (repository.updateDraft(latest.versionId(), header, title, body.maxResumes(), parts) != 1) {
                throw TestProblems.conflict();
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return new Saved(detail(repository.find(latest.versionId()).orElseThrow(TestProblems::conflict)), false);
    }

    @Transactional(readOnly = true)
    Detail get(UUID testId, Integer versionNo) {
        Header header = repository.header(testId).orElseThrow(TestProblems::notFound);
        requireStaff(header.examCode());
        return detail((versionNo == null ? repository.latest(testId) : repository.versionNo(testId, versionNo)).orElseThrow(TestProblems::notFound));
    }

    @Transactional(readOnly = true)
    List<Detail> versions(UUID testId) {
        Header header = repository.header(testId).orElseThrow(TestProblems::notFound);
        requireStaff(header.examCode());
        return repository.versions(testId).stream().map(this::detail).toList();
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
    UUID submit(UUID testId) {
        Header header = repository.header(testId).orElseThrow(TestProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        TestStaffView latest = repository.latest(testId).orElseThrow(TestProblems::notFound);
        if (!latest.status().editable()) {
            throw TestProblems.notEditable();
        }
        if (!latest.createdBy().equals(binding().userId())) {
            throw TestProblems.anotherAuthorsDraft();
        }
        Report report = report(latest);
        if (!report.blockers().isEmpty()) {
            throw TestProblems.skillMissing(report.blockers().stream().map(b -> new FieldProblem(b.skill(), "skill-missing", b.message())).toList());
        }
        return reviews.submit(new VersionRef(TestVersions.REVIEW_TYPE, latest.versionId()));
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------------------

    private static void title(String title, boolean required, List<FieldProblem> errors) {
        if (title == null || title.isBlank()) {
            errors.add(new FieldProblem("title", required ? "is required" : "cannot be blank"));
        } else if (title.strip().length() > 200) {
            errors.add(new FieldProblem("title", "is longer than 200 characters"));
        }
    }

    /** The parts of the request as rows, or the problems by JSON path. */
    private List<PartRow> structure(Header header, StructureBody body, List<FieldProblem> errors) {
        List<ExamSection> sections = exams.find(header.examCode()).map(ExamInfo::sections).orElse(List.of());
        List<PartBody> parts = body.parts();
        if (parts == null || parts.isEmpty()) {
            errors.add(new FieldProblem("parts", "needs at least one part"));
            return List.of();
        }
        if (parts.size() > sections.size()) {
            errors.add(new FieldProblem("parts", "has more parts than " + header.examCode() + " has sections (" + sections.size() + ")"));
            return List.of();
        }
        List<PartRow> rows = new ArrayList<>();
        Set<String> seenSections = new HashSet<>();
        Set<UUID> seenGroups = new HashSet<>();
        for (int i = 0; i < parts.size(); i++) {
            PartBody p = parts.get(i);
            String at = "parts[" + i + "]";
            if (p == null) {
                errors.add(new FieldProblem(at, "is required"));
                continue;
            }
            Optional<ExamSection> section = sections.stream().filter(s -> s.code().equals(p.sectionCode())).findFirst();
            if (section.isEmpty()) {
                errors.add(new FieldProblem(at + ".sectionCode", "is not a section of " + header.examCode() + " (" + sections.stream().map(ExamSection::code).toList() + ")"));
            } else if (!seenSections.add(p.sectionCode())) {
                errors.add(new FieldProblem(at + ".sectionCode", "is used by another part"));
            }
            if (p.durationSec() == null || p.durationSec() < 1 || p.durationSec() > MAX_DURATION_SEC) {
                errors.add(new FieldProblem(at + ".durationSec", "must be between 1 and " + MAX_DURATION_SEC + " seconds"));
            }
            List<UUID> ids = p.groups() == null ? List.of() : p.groups();
            if (ids.isEmpty()) {
                errors.add(new FieldProblem(at + ".groups", "needs at least one question group"));
            } else if (ids.size() > props.maxGroupsPerPart()) {
                errors.add(new FieldProblem(at + ".groups", "has more than " + props.maxGroupsPerPart() + " groups"));
            } else {
                for (int j = 0; j < ids.size(); j++) {
                    group(header, section, ids.get(j), at + ".groups[" + j + "]", seenGroups, errors);
                }
            }
            rows.add(new PartRow(p.sectionCode(), p.durationSec() == null ? 0 : p.durationSec(), ids));
        }
        return rows;
    }

    /** A group version the author can read, of this exam and owner, listed once, not retired, whose questions are of the type of the part's section. */
    private void group(Header header, Optional<ExamSection> section, UUID id, String at, Set<UUID> seen, List<FieldProblem> errors) {
        Optional<QuestionGroupVersionView> g = id == null ? Optional.empty() : groups.find(id);
        if (g.isEmpty() || !g.get().examCode().equals(header.examCode()) || !Objects.equals(g.get().ownerOrgId().orElse(null), header.ownerOrgId())) {
            errors.add(new FieldProblem(at, "is not a question group of this exam that you may use"));
            return;
        }
        if (!seen.add(id)) {
            errors.add(new FieldProblem(at, "is listed twice"));
            return;
        }
        if (g.get().status() == ContentStatus.ARCHIVED) {
            errors.add(new FieldProblem(at, "is an archived version: use the current one"));
            return;
        }
        if (section.isEmpty()) {
            return;
        }
        for (UUID questionVersionId : g.get().questionVersionIds()) {
            Optional<QuestionVersionView> q = questions.find(questionVersionId);
            Optional<String> typeSection = q.flatMap(v -> types.find(v.typeCode())).map(t -> t.section());
            if (typeSection.isPresent() && !typeSection.get().equals(section.get().code())) {
                errors.add(new FieldProblem(at, "has questions of section " + typeSection.get() + ", not " + section.get().code()));
                return;
            }
        }
    }

    private void overrides(Header header, List<OverrideBody> overrides, List<FieldProblem> errors) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        List<String> skills = exams.find(header.examCode()).map(ExamInfo::scoreSkills).orElse(List.of());
        List<TestRepository.Override> existing = repository.overrides(header.id());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < overrides.size(); i++) {
            OverrideBody o = overrides.get(i);
            String at = "scoreTableOverrides[" + i + "]";
            if (o == null || o.skill() == null || !skills.contains(o.skill())) {
                errors.add(new FieldProblem(at + ".skill", "is not a score of " + header.examCode() + " (it keeps " + skills + ")"));
            } else if (!seen.add(o.skill())) {
                errors.add(new FieldProblem(at + ".skill", "is listed twice"));
            } else if (existing.stream().noneMatch(e -> e.skill().equals(o.skill()) && e.scoreTableId().equals(o.scoreTableId()))) {
                errors.add(new FieldProblem(at + ".scoreTableId", "is not a conversion table of TEST scope for this test and skill"));
            } else if (header.variant() != null && o.skill().startsWith("READING_") && !o.skill().equals("READING_" + header.variant())) {
                errors.add(new FieldProblem(at + ".skill", "is the Reading table of another variant: this test is " + header.variant()));
            }
        }
    }

    // ---- the picture the CMS gets -------------------------------------------------------------------------------------------------

    private Detail detail(TestStaffView v) {
        List<ExamSection> sections = exams.find(v.examCode()).map(ExamInfo::sections).orElse(List.of());
        List<PartDetail> parts = new ArrayList<>();
        for (TestStaffView.Part p : v.parts()) {
            List<GroupRef> refs = new ArrayList<>();
            int count = 0;
            for (UUID id : p.groupVersionIds()) {
                Optional<QuestionGroupVersionView> g = groups.find(id);
                int n = g.map(x -> x.questionVersionIds().size()).orElse(0);
                count += n;
                refs.add(new GroupRef(id, g.map(QuestionGroupVersionView::groupId).orElse(null), g.map(x -> x.content().path("title").asText(null)).orElse(null), n));
            }
            String skill = sections.stream().filter(s -> s.code().equals(p.sectionCode())).map(ExamSection::skill).findFirst().orElse(null);
            parts.add(new PartDetail(p.position(), p.sectionCode(), skill, p.durationSec(), refs, count));
        }
        Report report = TestStructureValidator.validate(sections, parts.stream().map(p -> new TestStructureValidator.Part(p.sectionCode(), p.questions())).toList());
        return new Detail(v, parts, report, repository.overrides(v.testId()));
    }

    private Report report(TestStaffView v) {
        return detail(v).report();
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
                    return TestProblems.conflict();
                }
            }
        }
        return e;
    }
}
