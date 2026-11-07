package com.english9x.catalog.api;

import java.util.List;
import java.util.Optional;

/** The question types of the catalog: seeded by migration, immutable at run time, loaded and checked when the application starts. */
public interface QuestionTypes {

    /** @throws UnknownQuestionTypeException when {@code code} is not a seeded type */
    QuestionTypeDef get(String code);

    Optional<QuestionTypeDef> find(String code);

    /** Every type, ordered by exam, section position and code. */
    List<QuestionTypeDef> all();

    List<QuestionTypeDef> forExam(String examCode);
}
