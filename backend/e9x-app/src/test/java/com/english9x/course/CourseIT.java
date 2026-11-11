package com.english9x.course;

import com.english9x.content.test.TestIT;
import com.english9x.course.api.ProgressSource;
import com.english9x.domain.learning.LessonStatus;
import com.english9x.support.SeatFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Harness of the course tests: a TOEIC organisation (author, reviewer), PUBLISHED strategy lessons and tests to put in a course, the calls of the builder, and a
 * stand-in for the learning module's progress ({@link Progress}, which P3.2 replaces with the real thing).
 */
@Import(CourseIT.Progress.Config.class)
public abstract class CourseIT extends TestIT {

    /** What the learning module will tell the gate: set per lesson id by the test. */
    public static final class Progress implements ProgressSource {
        public static final Map<String, LessonStatus> DONE = new ConcurrentHashMap<>();
        /** Set by the learning tests (P3.2): the REAL source, which then answers instead of {@link #DONE}. */
        public static volatile ProgressSource delegate;

        @Override
        public Map<String, LessonStatus> progress(UUID userId, UUID courseVersionId) {
            ProgressSource real = delegate;
            if (real != null) {
                return real.progress(userId, courseVersionId);
            }
            return Map.copyOf(DONE);
        }

        @TestConfiguration
        public static class Config {
            @Bean
            @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
            ProgressSource testProgress() {
                return new Progress();
            }
        }
    }

    /** A strategy lesson version, PUBLISHED through the review; it plays an audio file (so that a learner's access to the file can be tested). */
    protected UUID publishedLesson(Staff s, String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("examCode", s.exam());
        body.put("title", title);
        body.put("blocks", List.of(Map.of("type", "paragraph", "text", "Read this."), Map.of("type", "audio", "audioMediaId", s.audio().toString())));
        body.put("questionTypeCodes", List.of("TOEIC_P5_INCOMPLETE_SENTENCE"));
        Res created = post("/api/cms/strategy-lessons", body, s.author(), s.org());
        if (created.status() != 201) {
            throw new AssertionError(created.status() + " " + created.raw());
        }
        Res submitted = post("/api/cms/strategy-lessons/" + created.body().path("lessonId").asText() + "/submit", null, s.author(), s.org());
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        return UUID.fromString(created.body().path("versionId").asText());
    }

    /** A test version, PUBLISHED through the review. */
    protected UUID publishedTest(Staff s, String title) {
        return approveTest(buildToeicTest(s, title).test(), s);
    }

    protected static Map<String, Object> lesson(String kind, UUID ref) {
        Map<String, Object> m = new HashMap<>();
        m.put("kind", kind);
        m.put("refId", ref.toString());
        return m;
    }

    protected static Map<String, Object> chapter(String title, Double threshold, Map<String, Object>... lessons) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        if (threshold != null) {
            m.put("passThreshold", threshold);
        }
        m.put("lessons", List.of(lessons));
        return m;
    }

    @SafeVarargs
    protected static Map<String, Object> courseBody(String exam, String code, String title, String rule, Map<String, Object>... chapters) {
        Map<String, Object> m = new HashMap<>();
        m.put("examCode", exam);
        m.put("code", code);
        m.put("title", title);
        m.put("description", "What this course teaches.");
        m.put("authorDisplay", "Ms Lan");
        m.put("unlockRule", rule);
        m.put("chapters", new ArrayList<>(List.of(chapters)));
        return m;
    }

    protected static String code() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    /** A DRAFT course. @return the response of the create */
    protected Res createCourse(Staff s, Map<String, Object> body) {
        return post("/api/cms/courses", body, s.author(), s.org());
    }

    /** Submits the draft course and approves it as the reviewer. */
    protected void publishCourse(Res course, Staff s) {
        publishCourse(course, s, s.author());
    }

    protected void publishCourse(Res course, Staff s, UUID author) {
        Res submitted = post("/api/cms/courses/" + course.body().path("courseId").asText() + "/submit", null, author, s.org());
        if (submitted.status() != 202) {
            throw new AssertionError(submitted.status() + " " + submitted.raw());
        }
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
    }

    /** A published course of the staff's organisation: chapter 1 = lesson, chapter test; chapter 2 = lesson. Lesson ids come back in the response (flatten). */
    protected Res publishedCourse(Staff s, String rule) {
        UUID l1 = publishedLesson(s, "Lesson one");
        UUID test = publishedTest(s, "Chapter test");
        UUID l2 = publishedLesson(s, "Lesson two");
        Res r = createCourse(s, courseBody(s.exam(), code(), "A course", rule,
                chapter("Chapter 1", 0.7, lesson("LESSON", l1), lesson("CHAPTER_TEST", test)), chapter("Chapter 2", 0.7, lesson("LESSON", l2))));
        if (r.status() != 201) {
            throw new AssertionError(r.status() + " " + r.raw());
        }
        publishCourse(r, s);
        return r;
    }

    /** The lesson ids of a course response, in order. */
    protected static List<UUID> lessonIds(Res course) {
        List<UUID> ids = new ArrayList<>();
        course.body().path("chapters").forEach(c -> c.path("lessons").forEach(l -> ids.add(UUID.fromString(l.path("id").asText()))));
        return ids;
    }

    /** The learner (a member of the staff's organisation) holds an entitlement for a day around now. */
    protected UUID learnerEntitled(Staff s, Res course) {
        UUID learner = user("learner");
        UUID courseId = UUID.fromString(course.body().path("courseId").asText());
        SeatFixtures.seatEntitlement(su, s.org(), learner, courseId, Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofDays(1)));
        return learner;
    }
}
