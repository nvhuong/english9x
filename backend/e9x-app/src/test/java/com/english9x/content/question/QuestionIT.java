package com.english9x.content.question;

import com.english9x.org.OrgIT;
import com.english9x.org.api.CurrentContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Harness of the question tests: an organisation with an AUTHOR and a REVIEWER of one exam, the valid example of every question type
 * (src/test/resources/catalog/examples, written by P2.1) with its file ids replaced by real media rows of the organisation and exam, and the
 * calls the CMS makes. Files are rows of media_assets written as the superuser (what the upload would have recorded): no MinIO is needed here.
 */
abstract class QuestionIT extends OrgIT {

    @org.springframework.beans.factory.annotation.Autowired
    protected com.english9x.workflow.api.ReviewService reviews;

    /** An organisation with an author and a reviewer of one exam, and one file of each kind the examples point at. */
    protected record Staff(UUID org, UUID author, UUID reviewer, String exam, UUID audio, UUID image) {}

    protected Staff staff(String exam) {
        UUID org = org("CENTER");
        UUID author = user("author");
        UUID reviewer = user("reviewer");
        grant(author, org, exam, "AUTHOR");
        grant(reviewer, org, exam, "REVIEWER");
        return new Staff(org, author, reviewer, exam, media(org, exam, "AUDIO", author), media(org, exam, "IMAGE", author));
    }

    protected UUID media(UUID org, String exam, String kind, UUID by) {
        UUID id = UUID.randomUUID();
        boolean image = kind.equals("IMAGE");
        su.update("insert into media_assets(id,owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) values (?,?,?,?,?,100,?,?,?)",
                id, org, exam, kind, image ? "image/png" : kind.equals("VIDEO") ? "video/mp4" : "audio/mpeg", image ? null : 1000L, "f." + kind, by);
        return id;
    }

    /** The valid example of a type: {"content": ..., "answerKey": ...}, its media ids replaced with the staff's real files of the right kind. */
    protected ObjectNode example(String code, Staff s) {
        try {
            ObjectNode n = (ObjectNode) JSON.readTree(Path.of("src/test/resources/catalog/examples/" + code + ".valid.json").toFile());
            substitute(n, s);
            return n;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void substitute(JsonNode node, Staff s) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            for (Iterator<String> it = o.fieldNames(); it.hasNext();) {
                String name = it.next();
                JsonNode v = o.get(name);
                if (name.endsWith("MediaId") && v.isTextual()) {
                    o.put(name, (name.startsWith("audio") ? s.audio() : s.image()).toString());
                } else {
                    substitute(v, s);
                }
            }
        } else if (node.isArray()) {
            ((ArrayNode) node).forEach(c -> substitute(c, s));
        }
    }

    protected Map<String, Object> body(String code, JsonNode example) {
        Map<String, Object> b = new HashMap<>();
        b.put("typeCode", code);
        b.put("content", example.get("content"));
        b.put("answerKey", example.get("answerKey"));
        b.put("explanation", "because");
        b.put("points", 1);
        return b;
    }

    protected Res createQuestion(String code, Staff s) {
        return post("/api/cms/questions", body(code, example(code, s)), s.author(), s.org());
    }

    protected Res put(String path, Object body, UUID asUser, UUID inOrg) {
        return call(org.springframework.http.HttpMethod.PUT, path, body, asUser, inOrg);
    }

    /** Runs as the person in the context, like a request thread does. */
    protected <T> T as(UUID user, UUID org, Supplier<T> action) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(user, org)) {
            return action.get();
        }
    }

    protected UUID submitAndApprove(Res created, Staff s) {
        UUID questionId = UUID.fromString(created.body().path("questionId").asText());
        Res submitted = post("/api/cms/questions/" + questionId + "/submit", null, s.author(), s.org());
        if (submitted.status() != 202) {
            throw new AssertionError("submit answered " + submitted.status() + " " + submitted.raw());
        }
        UUID reviewId = UUID.fromString(submitted.body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(reviewId);
            return null;
        });
        return questionId;
    }

}
