package com.english9x.course.internal;

import com.english9x.workflow.api.ContentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The CMS API of courses: the builder. Staff only (an AUTHOR or REVIEWER of the exam, in the context of the owner). */
@RestController
@RequestMapping("/api/cms/courses")
class CourseController {

    @Schema(name = "CmsCourseLessonRequest")
    record LessonRequest(
            @Schema(description = "LESSON (a strategy lesson version), CHAPTER_TEST or MOCK_TEST (a test version)") String kind,
            @Schema(description = "the version of the strategy lesson or test") UUID refId,
            @Schema(description = "how many attempts a learner has at a test; null = unlimited", nullable = true) Integer maxAttempts) {}

    @Schema(name = "CmsCourseChapterRequest")
    record ChapterRequest(String title,
                          @Schema(description = "score in [0, 1] a chapter test must reach under STRICT; default 0.7", nullable = true) Double passThreshold,
                          List<LessonRequest> lessons) {}

    @Schema(name = "CmsCourseRequest")
    record CourseRequest(
            @Schema(description = "the exam; fixed for the life of the course (on update: omit or repeat)", nullable = true, example = "IELTS") String examCode,
            @Schema(description = "exactly 6 characters, capital letters and digits; fixed for the life of the course (on update: omit or repeat)", nullable = true, example = "AB12CD") String code,
            String title,
            @Schema(nullable = true) String description,
            @Schema(nullable = true, description = "the author as the catalog shows them") String authorDisplay,
            @Schema(description = "STRICT, COMPLETION or FREE") String unlockRule,
            List<ChapterRequest> chapters) {}

    @Schema(name = "CmsCourseLesson")
    record LessonDto(UUID id, String kind, UUID refId, String title, @Schema(nullable = true) Integer maxAttempts) {}

    @Schema(name = "CmsCourseChapter")
    record ChapterDto(UUID id, int position, String title, double passThreshold, List<LessonDto> lessons) {}

    @Schema(name = "CmsCourseVersion")
    record VersionDto(UUID versionId, UUID courseId, String code, int versionNo, ContentStatus status, @Schema(nullable = true) UUID ownerOrgId, String examCode,
                      String title, String description, String authorDisplay, String unlockRule, List<ChapterDto> chapters, String sharedStatus, UUID createdBy,
                      Instant createdAt, Instant updatedAt) {
        static VersionDto of(CourseRepository.Version v) {
            return new VersionDto(v.versionId(), v.courseId(), v.code(), v.versionNo(), v.status(), v.ownerOrgId(), v.examCode(), v.title(), v.description(),
                    v.authorDisplay(), v.unlockRule(),
                    v.chapters().stream().map(c -> new ChapterDto(c.id(), c.position(), c.title(), c.passThreshold(),
                            c.lessons().stream().map(l -> new LessonDto(l.id(), l.kind(), l.refId(), l.title(), l.maxAttempts())).toList())).toList(),
                    v.sharedStatus(), v.createdBy(), v.createdAt(), v.updatedAt());
        }
    }

    @Schema(name = "CmsCourseSummary")
    record SummaryDto(UUID courseId, UUID versionId, String code, int versionNo, ContentStatus status, String examCode, String title, String sharedStatus,
                      int chapters, int lessons, UUID createdBy, Instant updatedAt) {}

    @Schema(name = "CmsCoursePage")
    record PageDto(List<SummaryDto> items, int offset, int limit) {}

    @Schema(name = "CmsCourseSubmitted")
    record SubmittedDto(UUID reviewId) {}

    private final CourseService service;

    CourseController(CourseService service) {
        this.service = service;
    }

    private static CourseService.Body body(CourseRequest r) {
        return new CourseService.Body(r.examCode(), r.code(), r.title(), r.description(), r.authorDisplay(), r.unlockRule(),
                r.chapters() == null ? null : r.chapters().stream().map(c -> c == null ? null : new CourseService.ChapterBody(c.title(), c.passThreshold(),
                        c.lessons() == null ? null : c.lessons().stream().map(l -> l == null ? null : new CourseService.LessonBody(l.kind(), l.refId(), l.maxAttempts())).toList())).toList());
    }

    @PostMapping
    @Operation(summary = "Create a course (version 1, a draft)", description = "422 with errors[] by JSON path (examCode, code, title, unlockRule, chapters[i]...); 409 course-code-taken.")
    ResponseEntity<VersionDto> create(@RequestBody CourseRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(VersionDto.of(service.create(body(body))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Save a course: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)", description = "422 with errors[] by JSON path.")
    ResponseEntity<VersionDto> update(@PathVariable UUID id, @RequestBody CourseRequest body) {
        CourseService.Saved saved = service.update(id, body(body));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(VersionDto.of(saved.version()));
    }

    @GetMapping
    @Operation(summary = "List courses (the latest version of each) the caller may read")
    ResponseEntity<PageDto> list(@RequestParam(required = false) String exam, @RequestParam(required = false) ContentStatus status,
                                 @RequestParam(required = false) Integer page) {
        CourseService.Page p = service.list(exam, status, page);
        List<SummaryDto> items = p.items().stream().map(s -> new SummaryDto(s.courseId(), s.versionId(), s.code(), s.versionNo(), s.status(), s.examCode(), s.title(),
                s.sharedStatus(), s.chapters(), s.lessons(), s.createdBy(), s.updatedAt())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageDto(items, p.offset(), p.limit()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One course: its latest version, or the numbered one")
    ResponseEntity<VersionDto> get(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(VersionDto.of(service.get(id, version)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Every version of a course, oldest first")
    ResponseEntity<List<VersionDto>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.versions(id).stream().map(VersionDto::of).toList());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the open draft for review", description = "The review approves it only when every lesson and test it names is PUBLISHED.")
    ResponseEntity<SubmittedDto> submit(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new SubmittedDto(service.submit(id)));
    }
}
