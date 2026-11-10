package com.english9x.content.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.ScoreTables;
import com.english9x.content.scoring.api.ScoreTableVersions;
import com.english9x.org.OrgIT;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.workflow.api.ReviewService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** /api/cms/score-tables (F15) over the real database, the real review workflow and real row-level security. English9x staff write the exam defaults. */
class ScoreTableServiceIT extends OrgIT {

    @Autowired ReviewService reviews;
    @Autowired ScoreTables scoreTables;
    @Autowired ScoreTableVersions versions;

    record Staff(UUID author, UUID coauthor, UUID reviewer) {}

    Staff staff;

    @BeforeEach
    void freshTables() {
        // the exam defaults are unique per exam and skill and the database is shared by the tests of the run: start each test without them
        su.execute("alter table score_table_versions disable trigger user");
        su.update("delete from score_table_versions where score_table_id in (select id from score_tables where function = 'TABLE')");
        su.execute("alter table score_table_versions enable trigger user");
        su.update("delete from score_tables where function = 'TABLE'");
        UUID author = user("author");
        UUID coauthor = user("coauthor");
        UUID reviewer = user("reviewer");
        grant(author, null, "IELTS", "AUTHOR");
        grant(coauthor, null, "IELTS", "AUTHOR");
        grant(reviewer, null, "IELTS", "REVIEWER");
        staff = new Staff(author, coauthor, reviewer);
    }

    static List<Map<String, Object>> rows(double... scaled) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < scaled.length; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("raw", i);
            r.put("scaled", scaled[i]);
            out.add(r);
        }
        return out;
    }

    static Map<String, Object> body(String skill, Object rows) {
        Map<String, Object> b = new HashMap<>();
        b.put("examCode", "IELTS");
        b.put("skill", skill);
        b.put("scope", "EXAM");
        b.put("rows", rows);
        return b;
    }

    Res put(String path, Object body, UUID asUser, UUID inOrg) {
        return call(org.springframework.http.HttpMethod.PUT, path, body, asUser, inOrg);
    }

    <T> T as(UUID user, Supplier<T> action) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(user, null)) {
            return action.get();
        }
    }

    Res create(String skill, Object rows) {
        return post("/api/cms/score-tables", body(skill, rows), staff.author(), null);
    }

    UUID approve(Res created) {
        Res submitted = post("/api/cms/score-tables/" + created.body().path("tableId").asText() + "/submit", null, staff.author(), null);
        assertEquals(202, submitted.status(), submitted.raw());
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        as(staff.reviewer(), () -> {
            reviews.approve(review);
            return null;
        });
        return review;
    }

    List<String> fields(Res r) {
        List<String> out = new ArrayList<>();
        r.body().path("errors").forEach(e -> out.add(e.path("field").asText()));
        return out;
    }

    long tablesOf(String skill) {
        return su.queryForObject("select count(*) from score_tables where exam_code = 'IELTS' and skill = ?", Long.class, skill);
    }

    @Test
    void rejectsNonContiguousRows() {
        // raw 0, 1, 3: 2 is missing, so a learner with 2 correct answers would have no score
        List<Map<String, Object>> gap = rows(0, 1, 3);
        gap.get(2).put("raw", 3);
        Res r = create("LISTENING", gap);
        assertEquals(422, r.status(), r.raw());
        assertEquals("urn:e9x:problem:score-table-invalid", r.problemType());
        assertEquals(List.of("rows"), fields(r));
        assertTrue(r.body().path("errors").get(0).path("message").asText().contains("contiguous"), r.raw());
        assertEquals(0, tablesOf("LISTENING"), "nothing was written");

        // not starting at 0, a scaled score that falls, a repeated raw, a raw that is not a whole number, a text instead of a number, no rows
        List<Map<String, Object>> from1 = rows(1, 2);
        from1.get(0).put("raw", 1);
        from1.get(1).put("raw", 2);
        assertEquals(422, create("LISTENING", from1).status());
        assertEquals(List.of("rows"), fields(create("LISTENING", rows(5, 4))));
        List<Map<String, Object>> twice = rows(1, 2, 3);
        twice.get(2).put("raw", 1);
        assertEquals(List.of("rows[2].raw"), fields(create("LISTENING", twice)));
        List<Map<String, Object>> fraction = rows(1, 2);
        fraction.get(1).put("raw", 1.5);
        assertEquals(List.of("rows[1].raw"), fields(create("LISTENING", fraction)));
        List<Map<String, Object>> text = rows(1, 2);
        text.get(1).put("scaled", "6");
        assertEquals(List.of("rows[1].scaled"), fields(create("LISTENING", text)));
        assertEquals(List.of("rows"), fields(create("LISTENING", List.of())));
        assertEquals(List.of("rows"), fields(create("LISTENING", null)));
        assertEquals(0, tablesOf("LISTENING"), "and none of them wrote anything");

        // a skill the exam keeps no score for, or an exam that is not in the catalog
        Res skill = create("SPEECH", rows(1, 2));
        assertEquals(422, skill.status());
        assertEquals(List.of("skill"), fields(skill));
        assertEquals(List.of("skill"), fields(create("LISTENING_X", rows(1))));

        // the valid one is accepted as a draft by the same author
        Res ok = create("LISTENING", rows(0, 1, 2, 3, 4));
        assertEquals(201, ok.status(), ok.raw());
        assertEquals("DRAFT", ok.body().path("status").asText());
        assertEquals(1, ok.body().path("versionNo").asInt());
        assertEquals(5, ok.body().path("rows").size());
        assertEquals("EXAM", ok.body().path("scope").asText());
    }

    @Test
    void publishedTableImmutable() {
        Res v1 = create("READING_AC", rows(0, 1, 2));
        assertEquals(201, v1.status(), v1.raw());
        UUID table = UUID.fromString(v1.body().path("tableId").asText());
        UUID version1 = UUID.fromString(v1.body().path("versionId").asText());

        // a draft is edited in place by its author only, and is frozen while it is under review
        assertEquals(200, put("/api/cms/score-tables/" + table, body("READING_AC", rows(0, 1, 3)), staff.author(), null).status());
        Res other = put("/api/cms/score-tables/" + table, body("READING_AC", rows(0, 2, 3)), staff.coauthor(), null);
        assertEquals(409, other.status(), other.raw());
        assertEquals("urn:e9x:problem:draft-of-another-author", other.problemType());
        assertEquals(409, post("/api/cms/score-tables/" + table + "/submit", null, staff.coauthor(), null).status());
        approve(v1);
        assertEquals("PUBLISHED", su.queryForObject("select status from score_table_versions where id = ?", String.class, version1));

        // the published rows are the ones the exam converts with
        var resolved = as(staff.author(), () -> scoreTables.resolve("IELTS", "READING_AC", Optional.empty()));
        assertEquals(version1, resolved.scoreTableVersionId());
        assertEquals(3.0, resolved.converter().orElseThrow().convert(2));

        // a published version cannot be changed, not by the application, not by the owner of the database: only archived
        for (String sql : List.of("update score_table_versions set entries = '[{\"raw\":0,\"scaled\":9}]' where id = ?",
                                  "update score_table_versions set status = 'DRAFT' where id = ?",
                                  "delete from score_table_versions where id = ?")) {
            assertThrows(DataAccessException.class, () -> su.update(sql, version1), sql);
        }
        assertEquals(List.of(0.0, 1.0, 3.0),
                su.queryForList("select (e->>'scaled')::float8 from score_table_versions, jsonb_array_elements(entries) e where id = ? order by 1", Double.class, version1));

        // editing it through the API makes version 2; version 1 is untouched
        Res v2 = put("/api/cms/score-tables/" + table, body("READING_AC", rows(0, 2, 4)), staff.author(), null);
        assertEquals(201, v2.status(), v2.raw());
        assertEquals(2, v2.body().path("versionNo").asInt());
        assertEquals("DRAFT", v2.body().path("status").asText());
        assertEquals("PUBLISHED", su.queryForObject("select status from score_table_versions where id = ?", String.class, version1));
        assertEquals(3.0, as(staff.author(), () -> scoreTables.resolve("IELTS", "READING_AC", Optional.empty())).converter().orElseThrow().convert(2),
                "conversions still use version 1 while version 2 is a draft");

        // under review it is frozen for its author too
        Res submitted = post("/api/cms/score-tables/" + table + "/submit", null, staff.author(), null);
        assertEquals(202, submitted.status(), submitted.raw());
        Res frozen = put("/api/cms/score-tables/" + table, body("READING_AC", rows(0, 3, 5)), staff.author(), null);
        assertEquals(409, frozen.status());
        assertEquals("urn:e9x:problem:version-under-review", frozen.problemType());
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        as(staff.reviewer(), () -> {
            reviews.approve(review);
            return null;
        });

        // version 2 is in force and version 1 was archived by the approval; the history is kept
        assertEquals(List.of("ARCHIVED", "PUBLISHED"),
                su.queryForList("select status from score_table_versions where score_table_id = ? order by version_no", String.class, table));
        assertEquals(4.0, as(staff.author(), () -> scoreTables.resolve("IELTS", "READING_AC", Optional.empty())).converter().orElseThrow().convert(2));
        Res history = get("/api/cms/score-tables/" + table + "/versions", staff.reviewer(), null);
        assertEquals(2, history.body().size());
        assertEquals(1, get("/api/cms/score-tables?exam=IELTS", staff.reviewer(), null).body().path("items").size() - skipFunctions(), "one line per table");

        // an archived (replaced) version is not the latest; the latest, version 2, is published again editable only as a new version
        assertEquals(201, put("/api/cms/score-tables/" + table, body("READING_AC", rows(0, 2, 5)), staff.author(), null).status());
        assertFalse(versions.find(version1).isEmpty());
    }

    /** The three aggregate functions the migration seeds also list for IELTS (WRITING, OVERALL): two of them. */
    private int skipFunctions() {
        return 2;
    }

    @Test
    void testScopeIsRefusedUntilTestsExist() {
        Map<String, Object> b = body("LISTENING", rows(0, 1));
        b.put("scope", "TEST");
        b.put("testId", UUID.randomUUID());
        Res r = post("/api/cms/score-tables", b, staff.author(), null);
        assertEquals(409, r.status(), r.raw());
        assertEquals("urn:e9x:problem:test-scope-unavailable", r.problemType());
        assertEquals(0, su.queryForObject("select count(*) from score_tables where scope = 'TEST'", Long.class));
        assertEquals(0, tablesOf("LISTENING"));
        // a table that exists cannot be turned into one either
        Res ok = create("LISTENING", rows(0, 1));
        assertEquals(409, put("/api/cms/score-tables/" + ok.body().path("tableId").asText(), b, staff.author(), null).status());
        // a test id means nothing for the exam default
        Map<String, Object> withTest = body("LISTENING", rows(0, 1));
        withTest.put("testId", UUID.randomUUID());
        Res stray = post("/api/cms/score-tables", withTest, staff.author(), null);
        assertEquals(422, stray.status(), stray.raw());
        assertEquals(List.of("testId"), fields(stray));
        // the scope is required and is one of the two
        Map<String, Object> none = body("LISTENING", rows(0, 1));
        none.remove("scope");
        assertEquals(422, post("/api/cms/score-tables", none, staff.author(), null).status());
        none.put("scope", "ORG");
        assertEquals(422, post("/api/cms/score-tables", none, staff.author(), null).status());
    }

    @Test
    void oneTablePerExamSkillAndTheFunctionsAreNotEditable() {
        Res first = create("SPEAKING", rows(0, 1));
        assertEquals(201, first.status(), first.raw());
        Res second = create("SPEAKING", rows(0, 2));
        assertEquals(409, second.status(), second.raw());
        assertEquals("urn:e9x:problem:score-table-exists", second.problemType());
        assertEquals(first.body().path("tableId").asText(), second.body().path("tableId").asText(), "it says which table to edit");
        // IELTS OVERALL is a function of the domain (seeded): there is nothing to put in a table
        UUID overall = su.queryForObject("select id from score_tables where exam_code = 'IELTS' and skill = 'OVERALL'", UUID.class);
        Res put = put("/api/cms/score-tables/" + overall, body("OVERALL", rows(0, 1)), staff.author(), null);
        assertEquals(422, put.status(), put.raw());
        assertEquals(409, create("OVERALL", rows(0, 1)).status(), "and a second one cannot be made beside it");
    }

    @Test
    void onlyTheStaffOfTheExamWriteAndRead() {
        UUID learner = user("learner");
        UUID toeicAuthor = user("toeic-author");
        grant(toeicAuthor, null, "TOEIC", "AUTHOR");
        UUID orgAuthor = user("org-author");
        UUID org = org("CENTER");
        grant(orgAuthor, org, "IELTS", "AUTHOR");
        assertEquals(403, post("/api/cms/score-tables", body("LISTENING", rows(0, 1)), learner, null).status());
        assertEquals(403, post("/api/cms/score-tables", body("LISTENING", rows(0, 1)), toeicAuthor, null).status(), "a TOEIC author writes no IELTS table");
        assertEquals(403, post("/api/cms/score-tables", body("LISTENING", rows(0, 1)), orgAuthor, org).status(), "the exam default is English9x's");
        assertEquals(0, tablesOf("LISTENING"));
        Res made = create("LISTENING", rows(0, 1));
        String id = made.body().path("tableId").asText();
        assertEquals(403, get("/api/cms/score-tables/" + id, learner, null).status());
        assertEquals(403, get("/api/cms/score-tables/" + id, toeicAuthor, null).status());
        assertEquals(403, get("/api/cms/score-tables?exam=IELTS", learner, null).status());
        assertEquals(200, get("/api/cms/score-tables/" + id, staff.reviewer(), null).status());
        // the reviewer does not write, the learner reaches nothing, nobody signed in is refused
        assertEquals(403, put("/api/cms/score-tables/" + id, body("LISTENING", rows(0, 2)), staff.reviewer(), null).status());
        assertEquals(401, get("/api/cms/score-tables/" + id, null, null).status());
        assertEquals(404, get("/api/cms/score-tables/" + UUID.randomUUID(), staff.author(), null).status());
    }
}
