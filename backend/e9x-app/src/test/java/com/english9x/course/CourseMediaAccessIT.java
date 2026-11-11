package com.english9x.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GET /api/media/{id}/url for a learner (reclaimed from P2.2): yes with an entitlement to a course that uses the file, no without one, both directions. */
class CourseMediaAccessIT extends CourseIT {

    @org.springframework.test.context.DynamicPropertySource
    static void minio(org.springframework.test.context.DynamicPropertyRegistry registry) {
        com.english9x.support.TestMinio.configure(registry);
    }

    @Test
    void entitledLearnerGetsTheLinkAndOthersDoNot() {
        Staff s = staff("TOEIC");
        Res course = publishedCourse(s, "FREE");
        UUID learner = learnerEntitled(s, course);
        Res ok = get("/api/media/" + s.audio() + "/url", learner, s.org());
        assertEquals(200, ok.status(), ok.raw());
        assertTrue(ok.body().path("url").asText().contains("X-Amz-Signature="));
        // an image of the same organisation that no course uses: no link, with the same answer as for an id nobody has
        UUID unused = media(s.org(), "TOEIC", "IMAGE", s.author());
        assertEquals(404, get("/api/media/" + unused + "/url", learner, s.org()).status());
        // a learner of the same organisation without an entitlement: refused
        UUID stranger = user("stranger");
        member(s.org(), stranger);
        Res no = get("/api/media/" + s.audio() + "/url", stranger, s.org());
        assertEquals(404, no.status(), no.raw());
        assertFalse(no.raw().contains("X-Amz"), no.raw());
        assertEquals(404, get("/api/media/" + UUID.randomUUID() + "/url", stranger, s.org()).status());
        // the entitlement of one course gives no other course's files
        Staff other = staff("TOEIC");
        Res otherCourse = publishedCourse(other, "FREE");
        assertEquals(404, get("/api/media/" + other.audio() + "/url", learner, s.org()).status());
        assertEquals(200, get("/api/media/" + other.audio() + "/url", learnerEntitled(other, otherCourse), other.org()).status());
        assertEquals(401, get("/api/media/" + s.audio() + "/url", null, null).status());
    }
}
