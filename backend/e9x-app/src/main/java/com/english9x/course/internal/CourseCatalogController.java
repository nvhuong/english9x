package com.english9x.course.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public course catalog (F20): a guest sees what a course teaches, never what is inside a lesson. The list holds the PUBLISHED English9x courses and the
 * APPROVED shared ones; the detail of one code is read under row-level security, so a person also sees their own organisation's PUBLISHED course by its code
 * and nobody sees a draft, an unshared course of another organisation or a code that does not exist (404 for all three).
 */
@RestController
class CourseCatalogController {

    @Schema(name = "CatalogCourse")
    record CourseDto(String code, String examCode, String title, String description, String authorDisplay, String unlockRule, int chapters, int lessons,
                     @Schema(description = "true when the course comes from the shared pool of an organisation") boolean shared) {}

    @Schema(name = "CatalogLesson")
    record LessonDto(UUID id, String kind, String title) {}

    @Schema(name = "CatalogChapter")
    record ChapterDto(String title, List<LessonDto> lessons) {}

    @Schema(name = "CatalogCourseOutline")
    record OutlineDto(String code, String examCode, String title, String description, String authorDisplay, String unlockRule, boolean shared,
                      List<ChapterDto> chapters) {}

    private final JdbcTemplate jdbc;
    private final CourseRepository courses;

    CourseCatalogController(JdbcTemplate jdbc, CourseRepository courses) {
        this.jdbc = jdbc;
        this.courses = courses;
    }

    @GetMapping("/api/catalog/courses")
    @Transactional(readOnly = true)
    @Operation(summary = "The public course catalog: PUBLISHED English9x courses and APPROVED shared ones; no sign-in needed")
    ResponseEntity<List<CourseDto>> list(@RequestParam(required = false) String exam) {
        List<CourseDto> out = jdbc.query("""
                select c.code, c.exam_code, v.title, v.description, v.author_display, v.unlock_rule, c.owner_org_id is not null,
                       (select count(*) from course_chapters h where h.course_version_id = v.id),
                       (select count(*) from course_lessons l where l.course_version_id = v.id)
                  from courses c join course_versions v on v.course_id = c.id and v.status = 'PUBLISHED'
                 where (c.owner_org_id is null or c.shared_status = 'APPROVED') and (?::text is null or c.exam_code = ?)
                 order by v.title, c.code
                """, (rs, n) -> new CourseDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getInt(8), rs.getInt(9), rs.getBoolean(7)), exam, exam);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @GetMapping("/api/catalog/courses/{code}")
    @Transactional(readOnly = true)
    @Operation(summary = "One course of the catalog by its code: description, chapters and lesson titles, author; never lesson content")
    ResponseEntity<OutlineDto> detail(@PathVariable String code) {
        record Head(UUID version, String code, String exam, String title, String description, String author, String rule, boolean shared) {}
        Head head = jdbc.query("""
                select v.id, c.code, c.exam_code, v.title, v.description, v.author_display, v.unlock_rule, c.owner_org_id is not null
                  from courses c join course_versions v on v.course_id = c.id and v.status = 'PUBLISHED'
                 where c.code = ?
                """, (rs, n) -> new Head(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getBoolean(8)), code).stream().findFirst().orElseThrow(CourseProblems::notFound);
        List<ChapterDto> chapters = courses.chapters(head.version()).stream()
                .map(c -> new ChapterDto(c.title(), c.lessons().stream().map(l -> new LessonDto(l.id(), l.kind(), l.title())).toList())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new OutlineDto(head.code(), head.exam(), head.title(), head.description(), head.author(), head.rule(), head.shared(), chapters));
    }
}
