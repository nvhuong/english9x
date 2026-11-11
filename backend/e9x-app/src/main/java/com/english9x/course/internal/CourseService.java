package com.english9x.course.internal;

import com.english9x.catalog.api.Exams;
import com.english9x.content.strategy.api.StrategyLessonVersionView;
import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.content.test.api.TestStaffView;
import com.english9x.content.test.api.TestVersions;
import com.english9x.course.api.CourseVersions;
import com.english9x.course.internal.CourseProblems.FieldProblem;
import com.english9x.course.internal.CourseRepository.ChapterRow;
import com.english9x.course.internal.CourseRepository.Header;
import com.english9x.course.internal.CourseRepository.LessonRow;
import com.english9x.course.internal.CourseRepository.Version;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Courses (F19): versioned content like tests. Who (an AUTHOR of the exam; the policies ask again), what state (only the latest version decides: own DRAFT /
 * CHANGES_REQUESTED edited in place, another author's refused, IN_REVIEW and ARCHIVED refused, PUBLISHED gives a NEW draft version), what content (a six-character
 * code, chapters with lessons that point at a version of a strategy lesson or a test of this exam and this owner).
 */
@Service
class CourseService {

    static final Pattern CODE = Pattern.compile("^[A-Z0-9]{6}$");
    private static final Set<String> KINDS = Set.of("LESSON", "CHAPTER_TEST", "MOCK_TEST");
    private static final Set<String> RULES = Set.of("STRICT", "COMPLETION", "FREE");
    private static final double DEFAULT_THRESHOLD = 0.7;
    private static final int MAX_CHAPTERS = 100;
    private static final int MAX_LESSONS = 500;

    record LessonBody(String kind, UUID refId, Integer maxAttempts) {}

    record ChapterBody(String title, Double passThreshold, List<LessonBody> lessons) {}

    /** @param examCode, code only for a new course (on an update they must match when given) */
    record Body(String examCode, String code, String title, String description, String authorDisplay, String unlockRule, List<ChapterBody> chapters) {}

    record Saved(Version version, boolean newVersion) {}

    record Page(List<CourseRepository.Summary> items, int offset, int limit) {}

    private static final int PAGE = 50;

    private final Authz authz;
    private final Exams exams;
    private final StrategyLessonVersions lessons;
    private final TestVersions tests;
    private final ReviewService reviews;
    private final CourseRepository repository;

    CourseService(Authz authz, Exams exams, StrategyLessonVersions lessons, TestVersions tests, ReviewService reviews, CourseRepository repository) {
        this.authz = authz;
        this.exams = exams;
        this.lessons = lessons;
        this.tests = tests;
        this.reviews = reviews;
        this.repository = repository;
    }

    @Transactional
    Version create(Body body) {
        List<FieldProblem> errors = new ArrayList<>();
        if (body.examCode() == null || body.examCode().isBlank()) {
            errors.add(new FieldProblem("examCode", "is required"));
        } else if (!exams.exists(body.examCode())) {
            errors.add(new FieldProblem("examCode", "is not an exam"));
        }
        if (!errors.isEmpty()) {
            throw CourseProblems.invalid(errors);
        }
        authz.require(Role.AUTHOR, Optional.of(body.examCode()));
        CurrentContextHolder.Binding who = binding();
        if (body.code() == null || !CODE.matcher(body.code()).matches()) {
            errors.add(new FieldProblem("code", "must be exactly 6 characters: capital letters A-Z and digits 0-9"));
        }
        Header header = new Header(UUID.randomUUID(), who.orgId().orElse(null), body.examCode(), body.code(), who.userId());
        List<ChapterRow> chapters = content(header, body, errors);
        if (!errors.isEmpty()) {
            throw CourseProblems.invalid(errors);
        }
        UUID versionId = UUID.randomUUID();
        try {
            repository.insertHeader(header, body.title().strip());
            repository.insertVersion(versionId, header, 1, body.title().strip(), text(body.description()), text(body.authorDisplay()), body.unlockRule(), chapters,
                    who.userId());
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return repository.find(versionId).orElseThrow(CourseProblems::conflict);
    }

    @Transactional
    Saved update(UUID courseId, Body body) {
        Header header = repository.header(courseId).orElseThrow(CourseProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        CurrentContextHolder.Binding who = binding();
        Version latest = repository.latest(courseId).orElseThrow(CourseProblems::notFound);
        switch (latest.status()) {
            case IN_REVIEW -> throw CourseProblems.underReview();
            case ARCHIVED -> throw CourseProblems.retired();
            case DRAFT, CHANGES_REQUESTED -> {
                if (!latest.createdBy().equals(who.userId())) {
                    throw CourseProblems.anotherAuthorsDraft();
                }
            }
            case PUBLISHED -> { }
        }
        List<FieldProblem> errors = new ArrayList<>();
        if (body.examCode() != null && !body.examCode().equals(header.examCode())) {
            errors.add(new FieldProblem("examCode", "cannot change"));
        }
        if (body.code() != null && !body.code().equals(header.code())) {
            errors.add(new FieldProblem("code", "cannot change"));
        }
        List<ChapterRow> chapters = content(header, body, errors);
        if (!errors.isEmpty()) {
            throw CourseProblems.invalid(errors);
        }
        try {
            if (latest.status() == ContentStatus.PUBLISHED) {
                UUID versionId = UUID.randomUUID();
                repository.insertVersion(versionId, header, latest.versionNo() + 1, body.title().strip(), text(body.description()), text(body.authorDisplay()),
                        body.unlockRule(), chapters, who.userId());
                return new Saved(repository.find(versionId).orElseThrow(CourseProblems::conflict), true);
            }
            if (repository.updateDraft(latest.versionId(), header, body.title().strip(), text(body.description()), text(body.authorDisplay()), body.unlockRule(),
                    chapters) != 1) {
                throw CourseProblems.conflict();
            }
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return new Saved(repository.find(latest.versionId()).orElseThrow(CourseProblems::conflict), false);
    }

    @Transactional(readOnly = true)
    Version get(UUID courseId, Integer versionNo) {
        Header header = repository.header(courseId).orElseThrow(CourseProblems::notFound);
        requireStaff(header.examCode());
        return (versionNo == null ? repository.latest(courseId) : repository.versionNo(courseId, versionNo)).orElseThrow(CourseProblems::notFound);
    }

    @Transactional(readOnly = true)
    List<Version> versions(UUID courseId) {
        Header header = repository.header(courseId).orElseThrow(CourseProblems::notFound);
        requireStaff(header.examCode());
        return repository.versions(courseId);
    }

    @Transactional(readOnly = true)
    Page list(String examCode, ContentStatus status, Integer page) {
        if (examCode != null && !examCode.isBlank()) {
            requireStaff(examCode);
        } else if (!authz.has(Role.AUTHOR, Optional.empty()) && !authz.has(Role.REVIEWER, Optional.empty())) {
            throw new AccessDeniedException("not an author or reviewer");
        }
        int offset = Math.max(0, page == null ? 0 : page) * PAGE;
        return new Page(repository.list(examCode == null || examCode.isBlank() ? null : examCode, status, PAGE, offset), offset, PAGE);
    }

    @Transactional
    UUID submit(UUID courseId) {
        Header header = repository.header(courseId).orElseThrow(CourseProblems::notFound);
        authz.require(Role.AUTHOR, Optional.of(header.examCode()));
        Version latest = repository.latest(courseId).orElseThrow(CourseProblems::notFound);
        if (!latest.status().editable()) {
            throw CourseProblems.notEditable();
        }
        if (!latest.createdBy().equals(binding().userId())) {
            throw CourseProblems.anotherAuthorsDraft();
        }
        return reviews.submit(new VersionRef(CourseVersions.REVIEW_TYPE, latest.versionId()));
    }

    // ---- the checks -----------------------------------------------------------------------------------------------------------------

    private static String text(String s) {
        return s == null ? "" : s.strip();
    }

    private List<ChapterRow> content(Header header, Body body, List<FieldProblem> errors) {
        if (body.title() == null || body.title().isBlank()) {
            errors.add(new FieldProblem("title", "is required"));
        } else if (body.title().strip().length() > 200) {
            errors.add(new FieldProblem("title", "is longer than 200 characters"));
        }
        if (text(body.description()).length() > 5000) {
            errors.add(new FieldProblem("description", "is longer than 5000 characters"));
        }
        if (text(body.authorDisplay()).length() > 200) {
            errors.add(new FieldProblem("authorDisplay", "is longer than 200 characters"));
        }
        if (body.unlockRule() == null || !RULES.contains(body.unlockRule())) {
            errors.add(new FieldProblem("unlockRule", "is required: one of " + RULES));
        }
        List<ChapterBody> chapters = body.chapters();
        if (chapters == null || chapters.isEmpty()) {
            errors.add(new FieldProblem("chapters", "needs at least one chapter"));
            return List.of();
        }
        if (chapters.size() > MAX_CHAPTERS) {
            errors.add(new FieldProblem("chapters", "has more than " + MAX_CHAPTERS + " chapters"));
            return List.of();
        }
        List<ChapterRow> rows = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < chapters.size(); i++) {
            ChapterBody c = chapters.get(i);
            String at = "chapters[" + i + "]";
            if (c == null) {
                errors.add(new FieldProblem(at, "is required"));
                continue;
            }
            if (c.title() == null || c.title().isBlank() || c.title().strip().length() > 200) {
                errors.add(new FieldProblem(at + ".title", "is required, at most 200 characters"));
            }
            double threshold = c.passThreshold() == null ? DEFAULT_THRESHOLD : c.passThreshold();
            if (!(threshold >= 0.0 && threshold <= 1.0)) {
                errors.add(new FieldProblem(at + ".passThreshold", "must be between 0 and 1"));
            }
            List<LessonBody> ls = c.lessons() == null ? List.of() : c.lessons();
            if (ls.isEmpty()) {
                errors.add(new FieldProblem(at + ".lessons", "needs at least one lesson"));
            }
            List<LessonRow> lessonRows = new ArrayList<>();
            for (int j = 0; j < ls.size(); j++) {
                lessonRows.add(lesson(header, ls.get(j), at + ".lessons[" + j + "]", errors));
                total++;
            }
            rows.add(new ChapterRow(c.title() == null ? "" : c.title().strip(), threshold, lessonRows));
        }
        if (total > MAX_LESSONS) {
            errors.add(new FieldProblem("chapters", "has more than " + MAX_LESSONS + " lessons"));
        }
        return rows;
    }

    private LessonRow lesson(Header header, LessonBody l, String at, List<FieldProblem> errors) {
        if (l == null || l.kind() == null || !KINDS.contains(l.kind())) {
            errors.add(new FieldProblem(at + ".kind", "is required: one of " + KINDS));
            return new LessonRow("LESSON", null, "", null);
        }
        if (l.maxAttempts() != null && (l.maxAttempts() < 1 || l.maxAttempts() > 100)) {
            errors.add(new FieldProblem(at + ".maxAttempts", "must be between 1 and 100"));
        }
        String title = null;
        if (l.refId() != null) {
            if (l.kind().equals("LESSON")) {
                Optional<StrategyLessonVersionView> v = lessons.find(l.refId());
                if (v.isPresent() && sameScope(header, v.get().examCode(), v.get().ownerOrgId().orElse(null)) && v.get().status() != ContentStatus.ARCHIVED) {
                    title = v.get().title();
                }
            } else {
                Optional<TestStaffView> v = tests.find(l.refId());
                if (v.isPresent() && sameScope(header, v.get().examCode(), v.get().ownerOrgId().orElse(null)) && v.get().status() != ContentStatus.ARCHIVED) {
                    title = v.get().title();
                }
            }
        }
        if (title == null) {
            errors.add(new FieldProblem(at + ".refId", l.kind().equals("LESSON")
                    ? "is not a strategy lesson version of this exam that you may use"
                    : "is not a test version of this exam that you may use"));
        }
        return new LessonRow(l.kind(), l.refId(), title == null ? "" : title, l.maxAttempts());
    }

    private static boolean sameScope(Header header, String examCode, UUID owner) {
        return header.examCode().equals(examCode) && java.util.Objects.equals(header.ownerOrgId(), owner);
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
                if ("23505".equals(sql.getSQLState())) {
                    return String.valueOf(sql.getMessage()).contains("courses_code_key") ? CourseProblems.codeTaken() : CourseProblems.conflict();
                }
                if ("23000".equals(sql.getSQLState())) {
                    return CourseProblems.conflict();
                }
            }
        }
        return e;
    }
}
