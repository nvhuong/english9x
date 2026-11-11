package com.english9x.course.internal;

import com.english9x.domain.learning.LessonStatus;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E only (profile {@code e2e}; the seed secret is checked by platform.e2e for every route under /api/test/seed): asks the REAL lesson gate as a person, with the progress
 * the test states, because the lesson page and the learning module that own the real progress arrive with P3.2.
 */
@RestController
@Profile("e2e")
class CourseGateProbe {

    record Done(UUID lessonId, Double score) {}

    record GateRequest(UUID userId, UUID orgId, UUID lessonId, List<Done> completed) {}

    private final CourseReads gate;

    CourseGateProbe(CourseReads gate) {
        this.gate = gate;
    }

    @PostMapping("/api/test/seed/course/gate")
    Map<String, Object> gate(@RequestBody GateRequest r) {
        Map<String, LessonStatus> progress = new HashMap<>();
        if (r.completed() != null) {
            r.completed().forEach(d -> progress.put(d.lessonId().toString(), new LessonStatus(true, d.score())));
        }
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(r.userId(), r.orgId())) {
            return Map.of("access", gate.check(r.userId(), r.lessonId(), Instant.now(), progress).name());
        }
    }
}
