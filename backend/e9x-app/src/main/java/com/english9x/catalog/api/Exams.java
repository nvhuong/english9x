package com.english9x.catalog.api;

import java.util.List;
import java.util.Optional;

/** The exams of the catalog. Read from the database on every call, so an exam added by a migration is known without a restart. */
public interface Exams {

    List<ExamInfo> all();

    Optional<ExamInfo> find(String code);

    boolean exists(String code);
}
