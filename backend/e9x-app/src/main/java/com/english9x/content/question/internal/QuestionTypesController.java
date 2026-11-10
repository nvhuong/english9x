package com.english9x.content.question.internal;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

/**
 * The forms of the question editor: for every question type, the JSON Schema of its content and of its answer key, as the server validates them. The
 * editor builds its form from these (react-jsonschema-form), so the form and the check cannot disagree. Only for the people who write or review
 * questions: the answer-key schema names the solution-side fields (transcript, sample answer), which a learner has no business knowing.
 */
@RestController
class QuestionTypesController {

    @Schema(name = "QuestionTypeForm")
    record QuestionTypeForm(String code, String examCode, String section, String kind,
                            @Schema(type = "object") JsonNode contentSchema, @Schema(type = "object") JsonNode answerKeySchema) {}

    private final QuestionTypes types;
    private final Authz authz;

    QuestionTypesController(QuestionTypes types, Authz authz) {
        this.types = types;
        this.authz = authz;
    }

    @GetMapping("/api/cms/question-types")
    @Transactional(readOnly = true)
    ResponseEntity<List<QuestionTypeForm>> forms(@RequestParam(required = false) String exam) {
        List<QuestionTypeDef> defs = exam == null || exam.isBlank() ? types.all() : types.forExam(exam);
        List<QuestionTypeForm> out = defs.stream()
                .filter(d -> authz.has(Role.AUTHOR, Optional.of(d.examCode())) || authz.has(Role.REVIEWER, Optional.of(d.examCode())))
                .map(d -> new QuestionTypeForm(d.code(), d.examCode(), d.section(), d.kind().name(), d.contentSchema().getSchemaNode(), d.answerKeySchema().getSchemaNode()))
                .toList();
        if (out.isEmpty() && !defs.isEmpty()) {
            throw new AccessDeniedException("not an author or reviewer");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
