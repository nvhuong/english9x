package com.english9x.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The study clock (F24 data): gaps longer than e9x.learning.idle-timeout are not study time; days are cut in the learner's own zone. */
class HeartbeatIT extends LearningIT {

    private Res beat(Course c, UUID who, String zone) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("courseId", c.courseId().toString(), "lessonId", c.lesson().toString()));
        if (zone != null) {
            body.put("timeZone", zone);
        }
        return post("/api/learning/heartbeat", body, who, c.staff().org());
    }

    /** 23:49 three evenings ago in Asia/Ho_Chi_Minh (16:49 UTC; the token is still valid for a clock in the past, and 00:10 there is still the same UTC day). */
    private Instant evening() {
        return LocalDate.now(ZoneOffset.UTC).minusDays(3).atTime(16, 49).toInstant(ZoneOffset.UTC);
    }

    @Test
    void idleGapNotCredited() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Instant t = evening();
        clock.set(t);
        assertEquals(0, beat(c, learner, null).body().path("todaySeconds").asInt());
        clock.set(t.plusSeconds(30));
        assertEquals(30, beat(c, learner, null).body().path("todaySeconds").asInt());
        clock.set(t.plusSeconds(60));
        assertEquals(60, beat(c, learner, null).body().path("todaySeconds").asInt());
        clock.set(t.plusSeconds(60).plus(Duration.ofMinutes(5))); // away for five minutes: not study time
        assertEquals(60, beat(c, learner, null).body().path("todaySeconds").asInt());
        clock.set(t.plusSeconds(90).plus(Duration.ofMinutes(5)));
        Res last = beat(c, learner, null);
        assertEquals(90, last.body().path("todaySeconds").asInt());
        assertEquals(90, su.queryForObject("select seconds from study_time_daily where user_id = ?", Integer.class, learner));
    }

    @Test
    void streakUsesUserZone() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Instant t = evening(); // 23:49 in Asia/Ho_Chi_Minh
        for (long s : new long[] {0, 60}) { // 23:49, 23:50: one minute on the first day
            clock.set(t.plusSeconds(s));
            assertEquals(200, beat(c, learner, null).status());
        }
        for (long s : new long[] {20 * 60, 21 * 60}) { // 00:09, 00:10 the next day there
            clock.set(t.plusSeconds(s));
            assertEquals(200, beat(c, learner, null).status());
        }
        assertEquals(2, su.queryForObject("select count(*) from study_time_daily where user_id = ?", Integer.class, learner), "two days in Asia/Ho_Chi_Minh");
        assertEquals(2, su.queryForObject("select current_days from study_streaks where user_id = ?", Integer.class, learner));
        assertEquals("Asia/Ho_Chi_Minh", su.queryForObject("select time_zone from study_streaks where user_id = ?", String.class, learner));
    }

    @Test
    void sameMomentsAreOneDayInUtc() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Instant t = evening();
        for (long s : new long[] {0, 60, 20 * 60, 21 * 60}) {
            clock.set(t.plusSeconds(s));
            assertEquals(200, beat(c, learner, "UTC").status());
        }
        assertEquals(1, su.queryForObject("select count(*) from study_time_daily where user_id = ?", Integer.class, learner), "one day in UTC");
        assertEquals(1, su.queryForObject("select current_days from study_streaks where user_id = ?", Integer.class, learner));
    }

    @Test
    void heartbeatOfAStrangerCountsNothing() {
        Course c = course("FREE", null);
        UUID stranger = stranger(c);
        assertEquals(403, beat(c, stranger, null).status());
        assertEquals(0, su.queryForObject("select count(*) from study_heartbeats where user_id = ?", Integer.class, stranger));
    }

    @Test
    void completedLessonCountsForTheDay() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Res r = post("/api/learning/lessons/" + c.lesson() + "/complete", null, learner, c.staff().org());
        assertEquals(1, r.body().path("streakDays").asInt(), "a completed lesson is enough for the day");
    }

    @Test
    void dailyTotalNeverShrinks() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Instant t = evening();
        for (long s : new long[] {0, 30, 60}) {
            clock.set(t.plusSeconds(s));
            beat(c, learner, null);
        }
        assertEquals(60, su.queryForObject("select seconds from study_time_daily where user_id = ?", Integer.class, learner));
        // a request that read the list before the others committed would compute less: the stored total must keep the larger value
        su.update("delete from study_heartbeats where user_id = ? and at > ?", learner, java.sql.Timestamp.from(t));
        clock.set(t.plus(Duration.ofMinutes(5))); // alone after an idle gap this credits nothing
        beat(c, learner, null);
        assertEquals(60, su.queryForObject("select seconds from study_time_daily where user_id = ?", Integer.class, learner), "the lower figure does not overwrite the higher");
    }

    @Test
    void rapidBeatsDoNotInflateRows() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Instant t = evening();
        for (long ms : new long[] {0, 100, 200, 5_000, 9_000}) { // all within half of the 30 s interval of the first
            clock.set(t.plusMillis(ms));
            assertEquals(200, beat(c, learner, null).status());
        }
        assertEquals(1, su.queryForObject("select count(*) from study_heartbeats where user_id = ?", Integer.class, learner));
        clock.set(t.plusSeconds(30));
        beat(c, learner, null);
        assertEquals(2, su.queryForObject("select count(*) from study_heartbeats where user_id = ?", Integer.class, learner));
    }
}
