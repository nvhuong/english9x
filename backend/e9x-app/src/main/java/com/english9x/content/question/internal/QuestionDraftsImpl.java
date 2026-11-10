package com.english9x.content.question.internal;

import com.english9x.content.question.api.QuestionDrafts;
import org.springframework.stereotype.Component;

/** {@link QuestionDrafts}: the one write path of the question service, offered to the import. */
@Component
class QuestionDraftsImpl implements QuestionDrafts {

    private final QuestionService service;

    QuestionDraftsImpl(QuestionService service) {
        this.service = service;
    }

    @Override
    public java.util.UUID create(Draft d) {
        return service.create(new QuestionService.Body(d.typeCode(), null, d.content(), d.answerKey(), d.explanation(), d.points())).versionId();
    }
}
