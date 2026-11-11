package com.english9x.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.workflow.api.UnpublishedDependencyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** /api/cms/courses (F19): who builds a course, what a structure may hold (checked by JSON path), the versions and the review. */
class CourseServiceIT extends CourseIT {

    private static List<String> fields(Res r) {
        List<String> at = new ArrayList<>();
        r.body().path("errors").forEach(e -> at.add(e.path("field").asText()));
        return at;
    }

    @Test
    void codeMustBeSixUppercaseAlnum() {
        Staff s = staff("TOEIC");
        UUID lesson = publishedLesson(s, "L");
        for (String bad : new String[] {"abc123", "ABC12", "ABC1234", "AB-123", "AB 123", "ÁBC123", ""}) {
            Res r = createCourse(s, courseBody("TOEIC", bad, "Course", "FREE", chapter("C1", null, lesson("LESSON", lesson))));
            assertEquals(422, r.status(), bad + " " + r.raw());
            assertTrue(fields(r).contains("code"), bad + " " + r.raw());
        }
        Map<String, Object> none = courseBody("TOEIC", null, "Course", "FREE", chapter("C1", null, lesson("LESSON", lesson)));
        assertTrue(fields(createCourse(s, none)).contains("code"), "a code is required");
        String ok = "A1B2C3";
        Res created = createCourse(s, courseBody("TOEIC", ok, "Course", "FREE", chapter("C1", null, lesson("LESSON", lesson))));
        assertEquals(201, created.status(), created.raw());
        assertEquals(ok, created.body().path("code").asText());
        assertEquals("DRAFT", created.body().path("status").asText());
        assertEquals(0.7, created.body().path("chapters").get(0).path("passThreshold").asDouble(), 1e-9, "the default threshold");
        // the code is unique across the whole platform, whoever owns the other course
        Res again = createCourse(s, courseBody("TOEIC", ok, "Another", "FREE", chapter("C1", null, lesson("LESSON", lesson))));
        assertEquals(409, again.status(), again.raw());
        assertEquals("urn:e9x:problem:course-code-taken", again.body().path("type").asText());
    }

    @Test
    void aStructureIsCheckedByPathAndByRole() {
        Staff s = staff("TOEIC");
        UUID lesson = publishedLesson(s, "L");
        Staff other = staff("TOEIC");
        UUID foreign = publishedLesson(other, "Not ours");
        Map<String, Object> body = courseBody("TOEIC", code(), " ", "SOMETIMES",
                chapter("", 1.5, lesson("LESSON", foreign), lesson("BOGUS", lesson)),
                chapter("C2", 0.5, lesson("CHAPTER_TEST", lesson)),   // a strategy lesson version is no test version
                chapter("C3", 0.5));
        Res r = createCourse(s, body);
        assertEquals(422, r.status(), r.raw());
        List<String> at = fields(r);
        for (String expected : List.of("title", "unlockRule", "chapters[0].title", "chapters[0].passThreshold", "chapters[0].lessons[0].refId",
                "chapters[0].lessons[1].kind", "chapters[1].lessons[0].refId", "chapters[2].lessons")) {
            assertTrue(at.contains(expected), expected + " in " + at);
        }
        assertEquals(422, createCourse(s, courseBody("TOEIC", code(), "x", "FREE")).status(), "a course has chapters");
        assertEquals(422, createCourse(s, courseBody("NOPE", code(), "x", "FREE", chapter("c", null, lesson("LESSON", lesson)))).status());
        // a reviewer and a learner do not create; nobody else reads
        Map<String, Object> good = courseBody("TOEIC", code(), "Course", "STRICT", chapter("C1", null, lesson("LESSON", lesson)));
        assertEquals(403, post("/api/cms/courses", good, s.reviewer(), s.org()).status());
        UUID learner = user("learner");
        member(s.org(), learner);
        grant(learner, s.org(), null, "LEARNER");
        assertEquals(403, post("/api/cms/courses", good, learner, s.org()).status());
        Res created = createCourse(s, good);
        assertEquals(201, created.status(), created.raw());
        String id = created.body().path("courseId").asText();
        assertEquals(200, get("/api/cms/courses/" + id, s.reviewer(), s.org()).status());
        assertEquals(404, get("/api/cms/courses/" + id, learner, s.org()).status());
        assertEquals(404, get("/api/cms/courses/" + id, other.author(), other.org()).status());
        assertEquals(404, put("/api/cms/courses/" + id, good, other.author(), other.org()).status());
        assertEquals(404, post("/api/cms/courses/" + id + "/submit", null, other.author(), other.org()).status());
        // the code and the exam are fixed
        Map<String, Object> changed = new java.util.HashMap<>(good);
        changed.put("code", code());
        assertTrue(fields(put("/api/cms/courses/" + id, changed, s.author(), s.org())).contains("code"));
    }

    @Test
    void aCourseWaitsForItsLessonsAndEditingAPublishedOneMakesVersion2() {
        Staff s = staff("TOEIC");
        // a lesson that is only a draft: the course can be written and submitted, but the review refuses to publish it
        Map<String, Object> draftLesson = new java.util.HashMap<>();
        draftLesson.put("examCode", "TOEIC");
        draftLesson.put("title", "Draft lesson");
        draftLesson.put("blocks", List.of(Map.of("type", "paragraph", "text", "x")));
        draftLesson.put("questionTypeCodes", List.of("TOEIC_P5_INCOMPLETE_SENTENCE"));
        UUID draft = UUID.fromString(post("/api/cms/strategy-lessons", draftLesson, s.author(), s.org()).body().path("versionId").asText());
        Res c = createCourse(s, courseBody("TOEIC", code(), "Waits", "FREE", chapter("C1", null, lesson("LESSON", draft))));
        assertEquals(201, c.status(), c.raw());
        String id = c.body().path("courseId").asText();
        Res submitted = post("/api/cms/courses/" + id + "/submit", null, s.author(), s.org());
        assertEquals(202, submitted.status(), submitted.raw());
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        assertThrows(UnpublishedDependencyException.class, () -> as(s.reviewer(), s.org(), () -> { reviews.approve(review); return 0; }));
        assertEquals(409, put("/api/cms/courses/" + id, courseBody("TOEIC", null, "x", "FREE", chapter("C", null, lesson("LESSON", draft))), s.author(), s.org()).status(),
                "under review: no edit");

        Res published = publishedCourse(s, "COMPLETION");
        String pid = published.body().path("courseId").asText();
        assertEquals("PUBLISHED", get("/api/cms/courses/" + pid, s.author(), s.org()).body().path("status").asText());
        UUID colleague = user("colleague");
        grant(colleague, s.org(), "TOEIC", "AUTHOR");
        Map<String, Object> edit = courseBody(null, null, "A course, revised", "STRICT", chapter("Only chapter", 0.9, lesson("LESSON",
                UUID.fromString(published.body().path("chapters").get(0).path("lessons").get(0).path("refId").asText()))));
        Res v2 = put("/api/cms/courses/" + pid, edit, colleague, s.org());
        assertEquals(201, v2.status(), v2.raw());
        assertEquals(2, v2.body().path("versionNo").asInt());
        List<String> statuses = new ArrayList<>();
        get("/api/cms/courses/" + pid + "/versions", colleague, s.org()).body().forEach(v -> statuses.add(v.path("status").asText()));
        assertEquals(List.of("PUBLISHED", "DRAFT"), statuses);
        assertEquals(409, put("/api/cms/courses/" + pid, edit, s.author(), s.org()).status(), "the first author cannot take the colleague's draft over");
        // publishing version 2 archives version 1 and the header follows
        publishCourse(v2, s, colleague);
        statuses.clear();
        get("/api/cms/courses/" + pid + "/versions", colleague, s.org()).body().forEach(v -> statuses.add(v.path("status").asText()));
        assertEquals(List.of("ARCHIVED", "PUBLISHED"), statuses);
        assertEquals("A course, revised", su.queryForObject("select title from courses where id = ?::uuid", String.class, pid), "the header mirrors the version in force");
        assertEquals("PUBLISHED", su.queryForObject("select status from courses where id = ?::uuid", String.class, pid));
    }
}
