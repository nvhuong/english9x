package com.english9x.learning;

import com.english9x.course.CourseIT;
import com.english9x.course.api.ProgressSource;
import com.english9x.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * Harness of the learning tests: a TOEIC organisation with a PUBLISHED course (a lesson with one embedded question, then a chapter test, then another lesson), a
 * learner with a plan, a clock the tests move by hand, and the REAL {@link ProgressSource} installed behind the stand-in of the course tests. A day counts in a
 * streak with one minute (e9x.learning.streak.min-minutes=1) so that a test does not need ten minutes of heartbeats.
 */
@Import(LearningIT.Config.class)
@TestPropertySource(properties = {"app.worker.enabled=false", "e9x.learning.streak.min-minutes=1"})
public abstract class LearningIT extends CourseIT {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MutableClock learningClock() {
            return new MutableClock(Instant.now());
        }
    }

    protected static final String P5 = "TOEIC_P5_INCOMPLETE_SENTENCE";

    @Autowired protected MutableClock clock;
    @Autowired List<ProgressSource> sources;

    @BeforeEach
    void realProgress() {
        Progress.DONE.clear();
        clock.set(Instant.now());
        Progress.delegate = sources.stream().filter(s -> !(s instanceof Progress)).findFirst().orElseThrow();
    }

    @AfterEach
    void standIn() {
        Progress.delegate = null;
    }

    protected record Course(Staff staff, Res res, UUID courseId, UUID lesson, UUID test, UUID lesson2, UUID question) {}

    /** Chapter 1: a lesson holding one P5 question, a chapter test (maxAttempts); chapter 2: a lesson. */
    protected Course course(String rule, Integer maxAttempts) {
        Staff s = staff("TOEIC");
        UUID question = publishedQuestion(P5, s);
        Map<String, Object> body = new HashMap<>();
        body.put("examCode", s.exam());
        body.put("title", "Lesson with a question");
        body.put("blocks", List.of(Map.of("type", "paragraph", "text", "Read this."), Map.of("type", "embedded-question", "questionVersionId", question.toString())));
        body.put("questionTypeCodes", List.of(P5));
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
        UUID lessonVersion = UUID.fromString(created.body().path("versionId").asText());
        UUID testVersion = publishedTest(s, "Chapter test");
        UUID l2 = publishedLesson(s, "Lesson two");
        Map<String, Object> test = lesson("CHAPTER_TEST", testVersion);
        test.put("maxAttempts", maxAttempts);
        Res r = createCourse(s, courseBody(s.exam(), code(), "A course", rule,
                chapter("Chapter 1", 0.5, lesson("LESSON", lessonVersion), test), chapter("Chapter 2", 0.5, lesson("LESSON", l2))));
        if (r.status() != 201) {
            throw new AssertionError(r.status() + " " + r.raw());
        }
        publishCourse(r, s);
        List<UUID> ids = lessonIds(r);
        return new Course(s, r, UUID.fromString(r.body().path("courseId").asText()), ids.get(0), ids.get(1), ids.get(2), question);
    }

    /** A learner of the organisation with a plan for the course, from forty days ago to sixty days ahead. */
    protected UUID learner(Course c) {
        UUID learner = user("learner");
        member(c.staff().org(), learner);
        com.english9x.support.SeatFixtures.seatEntitlement(su, c.staff().org(), learner, c.courseId(), Instant.now().minus(Duration.ofDays(40)), Instant.now().plus(Duration.ofDays(60)));
        return learner;
    }

    /** A member of the organisation with no plan at all. */
    protected UUID stranger(Course c) {
        UUID u = user("stranger");
        member(c.staff().org(), u);
        return u;
    }

    protected Res open(Course c, UUID who, UUID lesson) {
        return get("/api/learning/lessons/" + lesson, who, c.staff().org());
    }

    /** The right answer to a question of the seeded TOEIC test, by its type (P1 photo: A, P5 sentence: D). */
    protected static String rightAnswer(String typeCode) {
        return typeCode.equals("TOEIC_P1_PHOTO") ? "A" : "D";
    }

    protected Res sit(Course c, UUID who, boolean right) {
        Res view = get("/api/learning/chapter-tests/" + c.test(), who, c.staff().org());
        if (view.status() != 200) {
            return view;
        }
        List<Map<String, Object>> answers = new java.util.ArrayList<>();
        for (JsonNode g : view.body().path("groups")) {
            for (JsonNode q : g.path("questions")) {
                answers.add(Map.of("questionVersionId", q.path("versionId").asText(), "answer", right ? rightAnswer(q.path("typeCode").asText()) : "C"));
            }
        }
        return post("/api/learning/chapter-tests/" + c.test() + "/attempts", Map.of("answers", answers), who, c.staff().org());
    }
}
