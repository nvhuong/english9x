package com.english9x.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The public catalog (F20): outlines for everybody, never a lesson's content, never a draft, never another organisation's private course. */
class CatalogIT extends CourseIT {

    /** The English9x reviewer's decision, as the owner of the tables with the staff flag on (the policies and the approval trigger are db-tests'). */
    private void approveShared(String code) {
        su.execute("do $$ begin perform set_config('app.e9x_staff', 'on', true); update courses set shared_status = 'APPROVED' where code = '" + code + "'; end $$");
    }

    @Test
    void guestSeesOutlineNotContent() {
        // an organisation's course in the shared pool (English9x's own PUBLISHED tests and lessons would show up in every other test's lists, in this shared database)
        Staff e9 = staff("TOEIC");
        Res course = publishedCourse(e9, "STRICT");
        approveShared(course.body().path("code").asText());
        String code = course.body().path("code").asText();
        Res outline = get("/api/catalog/courses/" + code, null, null);
        assertEquals(200, outline.status(), outline.raw());
        assertEquals("A course", outline.body().path("title").asText());
        assertEquals("Ms Lan", outline.body().path("authorDisplay").asText());
        assertEquals("What this course teaches.", outline.body().path("description").asText());
        assertEquals(2, outline.body().path("chapters").size());
        assertEquals("Lesson one", outline.body().path("chapters").get(0).path("lessons").get(0).path("title").asText());
        assertEquals("CHAPTER_TEST", outline.body().path("chapters").get(0).path("lessons").get(1).path("kind").asText());
        // nothing of the inside: no blocks, no audio file id, no reference to the content version, no answer
        String raw = outline.raw();
        assertFalse(raw.contains(e9.audio().toString()), raw);
        for (String forbidden : new String[] {"blocks", "refId", "Read this.", "answerKey", "explanation", "audioMediaId", "SECRET"}) {
            assertFalse(raw.contains(forbidden), forbidden + " in " + raw);
        }
        // the list
        Res list = get("/api/catalog/courses?exam=TOEIC", null, null);
        assertEquals(200, list.status(), list.raw());
        boolean found = false;
        for (var c : list.body()) {
            found |= c.path("code").asText().equals(code);
            assertFalse(c.has("chapters") && c.path("chapters").isArray(), "the list has counts, not the outline");
        }
        assertTrue(found, list.raw());
        assertEquals(0, get("/api/catalog/courses?exam=IELTS", null, null).body().findValuesAsText("code").stream().filter(code::equals).count());
        assertEquals(404, get("/api/catalog/courses/NOSUCH", null, null).status());
    }

    @Test
    void draftsAndPrivateCoursesOfAnOrganisationAreNotPublic() {
        Staff a = staff("TOEIC");
        UUID lesson = publishedLesson(a, "L");
        Res draft = createCourse(a, courseBody("TOEIC", code(), "Draft", "FREE", chapter("C", null, lesson("LESSON", lesson))));
        String draftCode = draft.body().path("code").asText();
        assertEquals(404, get("/api/catalog/courses/" + draftCode, null, null).status(), "a draft");
        assertEquals(404, get("/api/catalog/courses/" + draftCode, a.author(), a.org()).status(), "not even the author: it is not published");
        Res published = publishedCourse(a, "FREE");
        String code = published.body().path("code").asText();
        assertEquals(404, get("/api/catalog/courses/" + code, null, null).status(), "an organisation's course is not public");
        assertEquals(200, get("/api/catalog/courses/" + code, a.author(), a.org()).status(), "its own organisation reads it by its code");
        Staff b = staff("TOEIC");
        assertEquals(404, get("/api/catalog/courses/" + code, b.author(), b.org()).status(), "another organisation does not");
        assertFalse(get("/api/catalog/courses", null, null).raw().contains(code));
        // the shared pool: an approved course is public, by the decision of an English9x reviewer only
        approveShared(code);
        assertEquals(200, get("/api/catalog/courses/" + code, null, null).status());
        assertTrue(get("/api/catalog/courses?exam=TOEIC", null, null).raw().contains(code));
    }
}
