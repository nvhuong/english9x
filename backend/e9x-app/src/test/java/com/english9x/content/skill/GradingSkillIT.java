package com.english9x.content.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.content.question.QuestionIT;
import com.english9x.content.skill.api.GradingSkillVersions;
import com.english9x.content.skill.api.PromptTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** /api/cms/grading-skills (F16) over the real database, the real review workflow and real row-level security. */
class GradingSkillIT extends QuestionIT {

    static final String TYPE = "IELTS_W_TASK2";
    static final List<String> CODES = List.of("task_response", "coherence_cohesion", "lexical_resource", "grammatical_range");
    static final String TEMPLATE = "You are an IELTS examiner.\n\nRubric:\n{{rubric}}\n\nCalibration samples:\n{{samples}}\n\nTask:\n{{task}}\n\nCandidate's answer:\n{{response}}\n";

    @Autowired GradingSkillVersions skills;

    private ArrayNode criteria(String... codes) {
        ArrayNode a = JSON.createArrayNode();
        for (String code : codes) {
            a.add(JSON.createObjectNode().put("code", code).put("name", code.replace('_', ' ')).put("min", 0).put("max", 9).put("step", 0.5));
        }
        return a;
    }

    private ObjectNode schema(String... declared) {
        ObjectNode props = JSON.createObjectNode();
        for (String code : declared) {
            props.set(code, JSON.createObjectNode().put("type", "number").put("minimum", 0).put("maximum", 9));
        }
        props.set("feedback", JSON.createObjectNode().put("type", "string"));
        ArrayNode required = JSON.createArrayNode();
        for (String code : declared) {
            required.add(code);
        }
        required.add("feedback");
        return JSON.createObjectNode().put("type", "object").<ObjectNode>set("properties", props).set("required", required);
    }

    private ArrayNode samples(Staff s) {
        ArrayNode a = JSON.createArrayNode();
        a.add(JSON.createObjectNode().put("band", 6.5).put("text", "Some people think... In my opinion").put("commentary", "Clear position, limited range."));
        a.add(JSON.createObjectNode().put("band", 8).put("mediaId", s.image().toString()).put("commentary", "Handwritten, fully developed."));
        return a;
    }

    private Map<String, Object> body(Staff s) {
        Map<String, Object> b = new HashMap<>();
        b.put("questionTypeCode", TYPE);
        b.put("rubric", "## Task response\nAnswers every part of the task.\n\n## Coherence\n...");
        b.put("criteria", criteria(CODES.toArray(String[]::new)));
        b.put("promptTemplate", TEMPLATE);
        b.put("samples", samples(s));
        b.put("resultSchema", schema(CODES.toArray(String[]::new)));
        return b;
    }

    private List<String> fields(Res r) {
        List<String> out = new ArrayList<>();
        r.body().path("errors").forEach(e -> out.add(e.path("field").asText()));
        return out;
    }

    private Res create(Staff s, Map<String, Object> b) {
        return post("/api/cms/grading-skills", b, s.author(), s.org());
    }

    @Test
    void promptRendersAllVariables() {
        Staff s = staff("IELTS");
        Res created = create(s, body(s));
        assertEquals(201, created.status(), created.raw());
        assertEquals("DRAFT", created.body().path("status").asText());
        assertEquals(1, created.body().path("versionNo").asInt());
        assertEquals("IELTS", created.body().path("examCode").asText());
        Res read = get("/api/cms/grading-skills/" + created.body().path("skillId").asText(), s.author(), s.org());
        String template = read.body().path("promptTemplate").asText();
        assertEquals(java.util.Set.copyOf(PromptTemplate.VARIABLES), PromptTemplate.variables(template), "the template names exactly the four variables");

        String rendered = PromptTemplate.render(template, Map.of(
                "rubric", "RUBRIC-TEXT", "samples", PromptTemplate.samplesText(read.body().path("samples")),
                "task", "TASK-TEXT", "response", "RESPONSE-{{rubric}}-TEXT"));
        assertTrue(rendered.contains("RUBRIC-TEXT"), rendered);
        assertTrue(rendered.contains("TASK-TEXT"), rendered);
        assertTrue(rendered.contains("RESPONSE-{{rubric}}-TEXT"), "a response that looks like a variable stays text and is not read again");
        assertTrue(rendered.contains("Sample 1 (band 6.5)") && rendered.contains("Some people think") && rendered.contains("Clear position, limited range."), rendered);
        assertTrue(rendered.contains("Sample 2 (band 8)") && rendered.contains(s.image().toString()), rendered);
        assertFalse(rendered.contains("{{samples}}") || rendered.contains("{{task}}") || rendered.contains("{{response}}"), "no variable is left unreplaced");
        assertEquals("a-1-b", PromptTemplate.render("a-{{ rubric }}-{{{task}}}", Map.of("rubric", "1", "task", "b")));

        // the template is refused when it lacks a variable, names another, or uses more than variables
        for (String bad : List.of("Rubric {{rubric}} {{samples}} {{task}}", "{{rubric}} {{samples}} {{task}} {{response}} {{student}}",
                                  "{{#samples}}{{rubric}}{{/samples}} {{samples}} {{task}} {{response}}", "{{rubric}} {{samples}} {{task}} {{response", "")) {
            Map<String, Object> b = body(s);
            b.put("promptTemplate", bad);
            Res r = create(s, b);
            assertEquals(422, r.status(), bad + " " + r.raw());
            assertEquals("urn:e9x:problem:grading-skill-invalid", r.problemType());
            assertEquals(List.of("promptTemplate"), fields(r), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> PromptTemplate.render("{{>partial}}", Map.of()));
    }

    @Test
    void resultSchemaMustDeclareEveryCriterion() {
        Staff s = staff("IELTS");
        Map<String, Object> missing = body(s);
        missing.put("resultSchema", schema("task_response", "coherence_cohesion", "lexical_resource"));   // grammatical_range is not declared
        Res r = create(s, missing);
        assertEquals(422, r.status(), r.raw());
        assertEquals("urn:e9x:problem:grading-skill-invalid", r.problemType());
        assertEquals(List.of("resultSchema.properties.grammatical_range"), fields(r));
        assertEquals(0, su.queryForObject("select count(*) from grading_skills where owner_org_id = ?", Long.class, s.org()), "nothing was written");

        // no properties at all, or a schema that is not an object schema
        Map<String, Object> bare = body(s);
        bare.put("resultSchema", JSON.createObjectNode().put("type", "object"));
        assertEquals(4, fields(create(s, bare)).size());
        Map<String, Object> notSchema = body(s);
        notSchema.put("resultSchema", JSON.createObjectNode().put("type", 5));
        assertEquals(List.of("resultSchema"), fields(create(s, notSchema)));
        Map<String, Object> text = body(s);
        text.put("resultSchema", "not an object");
        assertEquals(422, create(s, text).status());

        // a schema never reaches out: a $ref to another address, an $id, and $schema of another draft are refused
        for (JsonNode evil : List.of(
                schema(CODES.toArray(String[]::new)).put("$ref", "https://example.test/schema.json"),
                schema(CODES.toArray(String[]::new)).put("$id", "https://example.test/schema.json"),
                schema(CODES.toArray(String[]::new)).put("$schema", "http://json-schema.org/draft-04/schema#"))) {
            Map<String, Object> b = body(s);
            b.put("resultSchema", evil);
            Res refused = create(s, b);
            assertEquals(422, refused.status(), evil + " " + refused.raw());
            assertEquals(List.of("resultSchema"), fields(refused));
        }

        // declared but not usable: a property that accepts anything (or a string), a criterion the model may omit, a $ref that is not numeric
        ObjectNode anything = schema(CODES.toArray(String[]::new));
        ((ObjectNode) anything.get("properties")).set("lexical_resource", JSON.createObjectNode());
        ObjectNode stringy = schema(CODES.toArray(String[]::new));
        ((ObjectNode) stringy.get("properties")).set("lexical_resource", JSON.createObjectNode().put("type", "string"));
        ObjectNode optional = schema(CODES.toArray(String[]::new));
        ArrayNode req = JSON.createArrayNode();
        optional.get("required").forEach(n -> { if (!n.asText().equals("lexical_resource")) req.add(n); });
        optional.set("required", req);
        ObjectNode noRequired = schema(CODES.toArray(String[]::new));
        noRequired.remove("required");
        ObjectNode badRef = schema(CODES.toArray(String[]::new));
        ((ObjectNode) badRef.get("properties")).set("lexical_resource", JSON.createObjectNode().put("$ref", "#/$defs/label"));
        badRef.set("$defs", JSON.createObjectNode().<ObjectNode>set("label", JSON.createObjectNode().put("type", "string")));
        ObjectNode missingRef = schema(CODES.toArray(String[]::new));
        ((ObjectNode) missingRef.get("properties")).set("lexical_resource", JSON.createObjectNode().put("$ref", "#/$defs/nope"));
        for (ObjectNode weak : List.of(anything, stringy, badRef)) {
            Map<String, Object> b = body(s);
            b.put("resultSchema", weak);
            Res refused = create(s, b);
            assertEquals(422, refused.status(), weak + " " + refused.raw());
            assertEquals(List.of("resultSchema.properties.lexical_resource"), fields(refused), weak.toString());
        }
        Map<String, Object> dangling = body(s);
        dangling.put("resultSchema", missingRef);
        assertEquals(422, create(s, dangling).status(), "a $ref to nothing is refused (as the schema does not compile, or as not numeric)");
        Map<String, Object> omit = body(s);
        omit.put("resultSchema", optional);
        assertEquals(List.of("resultSchema.required"), fields(create(s, omit)), "a criterion the model may leave out");
        omit.put("resultSchema", noRequired);
        assertEquals(List.of("resultSchema.required"), fields(create(s, omit)), "no required list: every criterion may be left out");
        assertEquals(0, su.queryForObject("select count(*) from grading_skills where owner_org_id = ?", Long.class, s.org()), "nothing was written");

        // every criterion declared, required and numeric (and a local $ref to a number is fine): accepted
        Map<String, Object> ok = body(s);
        ObjectNode good = schema(CODES.toArray(String[]::new));
        ((ObjectNode) good.get("properties")).set("task_response", JSON.createObjectNode().put("$ref", "#/$defs/band"));
        good.set("$defs", JSON.createObjectNode().<ObjectNode>set("band", JSON.createObjectNode().put("type", "number").put("minimum", 0).put("maximum", 9)));
        ok.put("resultSchema", good);
        Res accepted = create(s, ok);
        assertEquals(201, accepted.status(), accepted.raw());
        assertEquals(CODES, java.util.stream.StreamSupport.stream(accepted.body().path("criteria").spliterator(), false).map(c -> c.path("code").asText()).toList());
    }

    @Test
    void criteriaSamplesAndTheTypeAreChecked() {
        Staff s = staff("IELTS");
        Map<String, Object> b = body(s);
        ArrayNode bad = criteria("a", "a", "b");
        ((ObjectNode) bad.get(2)).put("min", 9).put("max", 0);
        b.put("criteria", bad);
        b.put("resultSchema", schema("a", "b"));
        Res r = create(s, b);
        assertEquals(422, r.status(), r.raw());
        assertTrue(fields(r).contains("criteria[1].code"), r.raw());
        assertTrue(fields(r).contains("criteria[2].max"), r.raw());
        Map<String, Object> noCriteria = body(s);
        noCriteria.put("criteria", JSON.createArrayNode());
        assertEquals(List.of("criteria"), fields(create(s, noCriteria)));
        ArrayNode step = criteria("task_response", "coherence_cohesion", "lexical_resource", "grammatical_range");
        ((ObjectNode) step.get(0)).put("step", 0);
        ((ObjectNode) step.get(1)).put("step", 0.7);
        Map<String, Object> badStep = body(s);
        badStep.put("criteria", step);
        assertEquals(List.of("criteria[0].step", "criteria[1].step"), fields(create(s, badStep)));

        // a sample needs exactly one of text and mediaId, a band, and a file of this exam
        ArrayNode ss = JSON.createArrayNode();
        ss.add(JSON.createObjectNode().put("band", 7));
        ss.add(JSON.createObjectNode().put("band", 7).put("text", "x").put("mediaId", s.image().toString()));
        ss.add(JSON.createObjectNode().put("text", "x"));
        ss.add(JSON.createObjectNode().put("band", 7).put("mediaId", UUID.randomUUID().toString()));
        Map<String, Object> badSamples = body(s);
        badSamples.put("samples", ss);
        assertEquals(List.of("samples[0]", "samples[1]", "samples[2].band", "samples[3].mediaId"), fields(create(s, badSamples)));

        // the type: unknown, of a question an answer key scores by itself, or not a string
        Map<String, Object> auto = body(s);
        auto.put("questionTypeCode", "IELTS_R_SHORT_ANSWER");
        assertEquals(List.of("questionTypeCode"), fields(create(s, auto)));
        Map<String, Object> unknown = body(s);
        unknown.put("questionTypeCode", "NO_SUCH_TYPE");
        assertEquals(List.of("questionTypeCode"), fields(create(s, unknown)));
        // and the staff of another exam write nothing for it
        Staff toeic = staff("TOEIC");
        assertEquals(403, post("/api/cms/grading-skills", body(s), toeic.author(), toeic.org()).status());
    }

    @Test
    void oneSkillPerOwnerAndQuestionType() {
        Staff s = staff("IELTS");
        assertEquals(201, create(s, body(s)).status());
        Res again = create(s, body(s));
        assertEquals(409, again.status(), again.raw());
        assertEquals("urn:e9x:problem:grading-skill-exists", again.problemType());
        // another organisation has its own skill for the same type
        Staff other = staff("IELTS");
        assertEquals(201, create(other, body(other)).status());
        // and English9x's own, as English9x staff
        UUID e9 = user("e9-author");
        grant(e9, null, "IELTS", "AUTHOR");
        Map<String, Object> eb = body(s);
        eb.put("samples", JSON.createArrayNode());
        assertEquals(201, post("/api/cms/grading-skills", eb, e9, null).status());
    }

    @Test
    void reviewPublishesAndTeachersReadOnlyPublished() {
        Staff s = staff("IELTS");
        UUID teacher = user("teacher");
        grant(teacher, s.org(), "IELTS", "TEACHER");
        UUID learner = user("learner");
        member(s.org(), learner);
        Res created = create(s, body(s));
        String id = created.body().path("skillId").asText();
        UUID v1 = UUID.fromString(created.body().path("versionId").asText());

        assertEquals(404, get("/api/cms/grading-skills/" + id, learner, s.org()).status(), "a learner reads no skill: for them there is none");
        assertEquals(404, get("/api/cms/grading-skills/" + id, teacher, s.org()).status(), "a teacher reads no draft");
        Res another = put("/api/cms/grading-skills/" + id, body(s), s.reviewer(), s.org());
        assertEquals(403, another.status(), "a reviewer does not write");

        UUID review = UUID.fromString(post("/api/cms/grading-skills/" + id + "/submit", null, s.author(), s.org()).body().path("reviewId").asText());
        Res frozen = put("/api/cms/grading-skills/" + id, body(s), s.author(), s.org());
        assertEquals(409, frozen.status());
        assertEquals("urn:e9x:problem:version-under-review", frozen.problemType());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        assertEquals(200, get("/api/cms/grading-skills/" + id, teacher, s.org()).status(), "the teacher reads the published skill");
        assertEquals("PUBLISHED", get("/api/cms/grading-skills/" + id, teacher, s.org()).body().path("status").asText());
        assertEquals(404, get("/api/cms/grading-skills/" + id, learner, s.org()).status());
        assertTrue(as(s.author(), s.org(), () -> skills.find(v1)).isPresent());
        assertThrows(DataAccessException.class, () -> su.update("update grading_skill_versions set rubric = 'x' where id = ?", v1), "a published skill is immutable");

        // version 2 from the published one; version 1 is archived when 2 is published
        Map<String, Object> next = body(s);
        next.put("rubric", "a better rubric");
        Res v2 = put("/api/cms/grading-skills/" + id, next, s.author(), s.org());
        assertEquals(201, v2.status(), v2.raw());
        assertEquals(2, v2.body().path("versionNo").asInt());
        assertEquals("PUBLISHED", su.queryForObject("select status from grading_skill_versions where id = ?", String.class, v1));
        UUID review2 = UUID.fromString(post("/api/cms/grading-skills/" + id + "/submit", null, s.author(), s.org()).body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review2);
            return null;
        });
        assertEquals(List.of("ARCHIVED", "PUBLISHED"), su.queryForList("select status from grading_skill_versions where grading_skill_id = ?::uuid order by version_no", String.class, id));
        assertEquals("a better rubric", get("/api/cms/grading-skills/" + id, teacher, s.org()).body().path("rubric").asText());
        assertEquals(2, get("/api/cms/grading-skills/" + id + "/versions", s.reviewer(), s.org()).body().size());
        assertEquals(1, get("/api/cms/grading-skills?exam=IELTS", s.author(), s.org()).body().path("items").size());
    }

    @Test
    void dryRunWaitsForTheAiGrader() {
        Staff s = staff("IELTS");
        String id = create(s, body(s)).body().path("skillId").asText();
        Map<String, Object> run = new HashMap<>();
        run.put("response", "Some people believe that...");
        Res r = post("/api/cms/grading-skills/" + id + "/dry-run", run, s.author(), s.org());
        assertEquals(501, r.status(), r.raw());
        assertEquals("urn:e9x:problem:dry-run-unavailable", r.problemType());
        assertEquals(501, post("/api/cms/grading-skills/" + id + "/dry-run", run, s.reviewer(), s.org()).status());
        // the same gates as every call: unknown skill, a person who is not staff of the exam
        assertEquals(404, post("/api/cms/grading-skills/" + UUID.randomUUID() + "/dry-run", run, s.author(), s.org()).status());
        assertEquals(403, post("/api/cms/grading-skills/" + id + "/dry-run", run, user("nobody"), s.org()).status(), "not even a member of the organisation");
        UUID member = user("member");
        member(s.org(), member);
        assertEquals(404, post("/api/cms/grading-skills/" + id + "/dry-run", run, member, s.org()).status(), "a member without a role sees no skill");
    }
}
