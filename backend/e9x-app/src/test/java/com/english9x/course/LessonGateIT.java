package com.english9x.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.billing.api.Entitlements;
import com.english9x.course.api.CourseOutline;
import com.english9x.course.api.LessonAccess;
import com.english9x.course.api.LessonGate;
import com.english9x.course.api.LessonNotFoundException;
import com.english9x.domain.learning.ChapterRef;
import com.english9x.domain.learning.LessonKind;
import com.english9x.domain.learning.LessonStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** LessonGate: the entitlement first, then the unlock rule of the course over the learner's progress. */
class LessonGateIT extends CourseIT {

    @Autowired
    LessonGate gate;
    @Autowired
    CourseOutline outline;

    @BeforeEach
    void noProgress() {
        Progress.DONE.clear();
    }

    private LessonAccess check(Staff s, UUID learner, UUID lesson) {
        return as(learner, s.org(), () -> gate.check(learner, lesson, Instant.now()));
    }

    @Test
    void strictLocksUntilChapterTestPassed() {
        Staff s = staff("TOEIC");
        Res course = publishedCourse(s, "STRICT");
        UUID learner = learnerEntitled(s, course);
        List<UUID> ids = lessonIds(course); // lesson 1, chapter test, lesson 2
        assertEquals(LessonAccess.ALLOWED, check(s, learner, ids.get(0)), "the first lesson is always open");
        assertEquals(LessonAccess.LOCKED, check(s, learner, ids.get(1)), "the chapter test waits for the lesson");
        assertEquals(LessonAccess.LOCKED, check(s, learner, ids.get(2)));
        Progress.DONE.put(ids.get(0).toString(), new LessonStatus(true, null));
        assertEquals(LessonAccess.ALLOWED, check(s, learner, ids.get(1)));
        // the chapter test is done but under the threshold (0.7): the next chapter stays shut
        Progress.DONE.put(ids.get(1).toString(), new LessonStatus(true, 0.5));
        assertEquals(LessonAccess.LOCKED, check(s, learner, ids.get(2)));
        Progress.DONE.put(ids.get(1).toString(), new LessonStatus(true, 0.7));
        assertEquals(LessonAccess.ALLOWED, check(s, learner, ids.get(2)), "at the threshold it opens");
    }

    @Test
    void completionIgnoresScore() {
        Staff s = staff("TOEIC");
        Res course = publishedCourse(s, "COMPLETION");
        UUID learner = learnerEntitled(s, course);
        List<UUID> ids = lessonIds(course);
        assertEquals(LessonAccess.LOCKED, check(s, learner, ids.get(2)));
        Progress.DONE.put(ids.get(0).toString(), new LessonStatus(true, null));
        Progress.DONE.put(ids.get(1).toString(), new LessonStatus(true, 0.1));
        assertEquals(LessonAccess.ALLOWED, check(s, learner, ids.get(2)), "finished is enough, whatever the score");
    }

    @Test
    void freeOpensAll() {
        Staff s = staff("TOEIC");
        Res course = publishedCourse(s, "FREE");
        UUID learner = learnerEntitled(s, course);
        for (UUID lesson : lessonIds(course)) {
            assertEquals(LessonAccess.ALLOWED, check(s, learner, lesson));
        }
    }

    @Test
    void notEntitledBeatsUnlock() {
        Staff s = staff("TOEIC");
        Res course = publishedCourse(s, "STRICT");
        List<UUID> ids = lessonIds(course);
        UUID stranger = user("stranger");
        member(s.org(), stranger);
        assertEquals(LessonAccess.NOT_ENTITLED, check(s, stranger, ids.get(0)), "even the open first lesson");
        assertEquals(LessonAccess.NOT_ENTITLED, check(s, stranger, ids.get(2)), "NOT_ENTITLED, not LOCKED, for a lesson that is also locked");
        // an entitlement that has ended
        UUID expired = user("expired");
        com.english9x.support.SeatFixtures.seatEntitlement(su, s.org(), expired, UUID.fromString(course.body().path("courseId").asText()),
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        assertEquals(LessonAccess.NOT_ENTITLED, check(s, expired, ids.get(0)));
        // and a lesson nobody has
        assertThrows(LessonNotFoundException.class, () -> check(s, stranger, UUID.randomUUID()));
    }

    @Test
    void outlineListsChaptersAndLessonsInOrder() {
        Staff s = staff("TOEIC");
        Res course = publishedCourse(s, "STRICT");
        UUID learner = learnerEntitled(s, course);
        UUID version = UUID.fromString(course.body().path("versionId").asText());
        List<ChapterRef> chapters = as(learner, s.org(), () -> outline.lessonsInOrder(version));
        assertEquals(2, chapters.size());
        assertEquals(0.7, chapters.get(0).passThreshold(), 1e-9);
        assertEquals(List.of(LessonKind.LESSON, LessonKind.CHAPTER_TEST), chapters.get(0).lessons().stream().map(l -> l.kind()).toList());
        assertEquals(lessonIds(course).stream().map(UUID::toString).toList(),
                chapters.stream().flatMap(c -> c.lessons().stream()).map(l -> l.id()).toList());
    }
}
