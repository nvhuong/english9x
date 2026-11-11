package com.english9x.platform.e2e;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * E2E seeds of automatic scoring (P4.1), under the profile {@code e2e} and the seed secret like the others. Scoring needs a PUBLISHED score table before
 * it can convert (P2.1 seeds no table values: spec 10.2 gives none), and the results page that shows a score is P4.4: until then the specs read the score
 * rows through here. The table is made as the TEST override of one test (its own row, so that no spec's table collides with another's in the shared
 * database), written as the table owner exactly like the review would leave it: header, PUBLISHED version 1.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/test/seed/grading")
class GradingSeedController {

    private final SeedDatabase db;

    GradingSeedController(SeedDatabase db) {
        this.db = db;
    }

    record ScoreTableRequest(UUID testVersionId, String skill, List<Double> scaled) {}

    /** A PUBLISHED TEST-scope table for the test of that version: {@code scaled[raw]} is the score for {@code raw} right answers. */
    @PostMapping("/score-table")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> scoreTable(@RequestBody ScoreTableRequest r) {
        if (r.testVersionId() == null || r.skill() == null || r.scaled() == null || r.scaled().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "testVersionId, skill and scaled are required");
        }
        Map<String, Object> test = db.jdbc().queryForList("select test_id, owner_org_id, exam_code from test_versions where id = ?", r.testVersionId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such test version"));
        UUID header = db.jdbc().queryForList("select id from score_tables where scope = 'TEST' and test_id = ? and skill = ?", UUID.class, test.get("test_id"), r.skill()).stream().findFirst()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    db.jdbc().update("insert into score_tables(id, owner_org_id, exam_code, skill, scope, test_id) values (?,?,?,?, 'TEST', ?)", id, test.get("owner_org_id"), test.get("exam_code"),
                            r.skill(), test.get("test_id"));
                    return id;
                });
        StringBuilder entries = new StringBuilder("[");
        for (int raw = 0; raw < r.scaled().size(); raw++) {
            entries.append(raw == 0 ? "" : ",").append("{\"raw\":").append(raw).append(",\"scaled\":").append(r.scaled().get(raw)).append('}');
        }
        int versionNo = db.jdbc().queryForObject("select coalesce(max(version_no), 0) + 1 from score_table_versions where score_table_id = ?", Integer.class, header);
        UUID version = db.jdbc().queryForObject("insert into score_table_versions(score_table_id, owner_org_id, exam_code, version_no, status, entries) values (?,?,?,?, 'PUBLISHED', ?::jsonb) returning id",
                UUID.class, header, test.get("owner_org_id"), test.get("exam_code"), versionNo, entries.append(']').toString());
        return Map.of("scoreTableId", header.toString(), "versionId", version.toString());
    }

    record AttemptRef(UUID attemptId) {}

    /** What scoring wrote for an attempt: the grading record (interrupted flag, announced) and one row per skill. */
    @PostMapping("/scores")
    Map<String, Object> scores(@RequestBody AttemptRef r) {
        List<Map<String, Object>> rows = new ArrayList<>();
        db.jdbc().query("select skill, kind, source, raw::float8 as raw, raw_max::float8 as raw_max, scaled::float8 as scaled, score_table_version_id::text as version from scores where attempt_id = ? order by skill", rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skill", rs.getString("skill"));
            row.put("kind", rs.getString("kind"));
            row.put("source", rs.getString("source"));
            row.put("raw", rs.getObject("raw"));
            row.put("rawMax", rs.getObject("raw_max"));
            row.put("scaled", rs.getObject("scaled"));
            row.put("scoreTableVersionId", rs.getString("version"));
            rows.add(row);
        }, r.attemptId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scores", rows);
        db.jdbc().query("select interrupted, announced_at is not null as announced from attempt_gradings where attempt_id = ?", rs -> {
            out.put("graded", true);
            out.put("interrupted", rs.getBoolean("interrupted"));
            out.put("announced", rs.getBoolean("announced"));
        }, r.attemptId());
        out.putIfAbsent("graded", false);
        return out;
    }
}
