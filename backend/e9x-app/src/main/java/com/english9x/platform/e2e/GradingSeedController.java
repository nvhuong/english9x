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

    /** What AI grading wrote for an attempt: its state (DONE, AI_FAILED, TEACHER_QUEUE; none while it has not decided) and one row per evaluated response. */
    @PostMapping("/ai")
    Map<String, Object> ai(@RequestBody AttemptRef r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", db.jdbc().queryForList("select state from ai_gradings where attempt_id = ?", String.class, r.attemptId()).stream().findFirst().orElse(null));
        List<Map<String, Object>> rows = new ArrayList<>();
        db.jdbc().query("select e.model, e.input_tokens, e.output_tokens, e.cost_vnd::float8 as cost, e.band::float8 as band, e.result::text as result, q.section_code "
                + "from ai_evaluations e join attempt_questions q on q.attempt_id = e.attempt_id and q.question_id = e.question_id where e.attempt_id = ? order by q.section_code", rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("section", rs.getString("section_code"));
            row.put("model", rs.getString("model"));
            row.put("inputTokens", rs.getInt("input_tokens"));
            row.put("outputTokens", rs.getInt("output_tokens"));
            row.put("costVnd", rs.getDouble("cost"));
            row.put("band", rs.getDouble("band"));
            row.put("result", rs.getString("result"));
            rows.add(row);
        }, r.attemptId());
        out.put("evaluations", rows);
        return out;
    }

    record PolicyRequest(UUID testVersionId, String mode) {}

    /**
     * The grading mode of one test (a TEST-scope policy row, owner and exam of the test, written as the table owner like the administrator's screen would), so that a spec can have
     * its own attempt graded by the teachers (TEACHER) or by AI and teachers (BOTH) without touching the default of the exam, which every other spec shares.
     */
    @PostMapping("/policy")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> policy(@RequestBody PolicyRequest r) {
        if (r.testVersionId() == null || r.mode() == null || !List.of("AI", "TEACHER", "BOTH").contains(r.mode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "testVersionId and a mode (AI, TEACHER, BOTH) are required");
        }
        Map<String, Object> test = db.jdbc().queryForList("select test_id, owner_org_id, exam_code from test_versions where id = ?", r.testVersionId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such test version"));
        db.jdbc().update("insert into grading_policies(owner_org_id, exam_code, scope, test_id, mode) values (?,?, 'TEST', ?, ?) on conflict (owner_org_id, test_id) where scope = 'TEST' do update set mode = excluded.mode",
                test.get("owner_org_id"), test.get("exam_code"), test.get("test_id"), r.mode());
        return Map.of("mode", r.mode());
    }

    /** The teachers' queue item of an attempt (none until the policy or the AI put it there) and the reviews written for it. */
    @PostMapping("/queue")
    Map<String, Object> queue(@RequestBody AttemptRef r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", db.jdbc().queryForList("select id::text as id, state, reason, grading_org_id::text as grading_org_id, claimed_by::text as claimed_by, version from grading_queue_items where attempt_id = ?", r.attemptId())
                .stream().findFirst().orElse(null));
        List<Map<String, Object>> reviews = new ArrayList<>();
        db.jdbc().query("select skill, teacher_id::text as teacher_id, band::float8 as band, ai_band::float8 as ai_band, deviation::float8 as deviation, comment from teacher_reviews where attempt_id = ? order by skill", rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skill", rs.getString("skill"));
            row.put("teacherId", rs.getString("teacher_id"));
            row.put("band", rs.getDouble("band"));
            row.put("aiBand", rs.getObject("ai_band"));
            row.put("deviation", rs.getObject("deviation"));
            row.put("comment", rs.getString("comment"));
            reviews.add(row);
        }, r.attemptId());
        out.put("reviews", reviews);
        return out;
    }

    /**
     * The deadline of the claim on an attempt's item passes (the real default is 24 hours, which a spec cannot wait out): the claim's deadline moves into the past, and the
     * real {@code release_expired_claim} job (the E2E stack runs it every few seconds) returns the item to the queue.
     */
    @PostMapping("/queue/expire")
    Map<String, Object> expire(@RequestBody AttemptRef r) {
        UUID attempt = r.attemptId();
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attemptId is required");
        }
        db.jdbc().execute("DO $$ BEGIN ALTER TABLE grading_queue_items DISABLE TRIGGER grading_queue_items_guard; "
                + "UPDATE grading_queue_items SET claim_due_at = now() - interval '1 minute' WHERE attempt_id = '" + attempt + "' AND state = 'CLAIMED'; "
                + "ALTER TABLE grading_queue_items ENABLE TRIGGER grading_queue_items_guard; END $$");
        return Map.of("expired", true);
    }
}
