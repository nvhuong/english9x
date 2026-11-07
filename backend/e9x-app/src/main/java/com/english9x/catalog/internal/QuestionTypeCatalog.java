package com.english9x.catalog.internal;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.catalog.api.UnknownQuestionTypeException;
import com.english9x.domain.scoring.InteractionKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The question types: the rows of {@code question_types} (seeded by Flyway) joined to their schema files. Built once, when the
 * application starts, and the build is the check: a seeded type without a schema file, a schema file without a seeded type, a
 * schema that is not valid JSON Schema, a kind the domain does not know, or an {@code interaction_kinds} table that differs from the
 * domain's {@link InteractionKind} stops the start-up with a message naming the type. Nothing here changes while the application runs.
 */
@Component
final class QuestionTypeCatalog implements QuestionTypes, SmartInitializingSingleton {

    /** A row of {@code question_types}; {@code kind} is the interaction kind's name as stored. */
    record TypeRow(String code, String examCode, String section, String kind, Optional<String> excelTemplateId) {}

    private final JdbcTemplate jdbc;
    private final SchemaFiles files;
    private volatile Map<String, QuestionTypeDef> byCode;

    @Autowired
    QuestionTypeCatalog(JdbcTemplate jdbc) {
        this(jdbc, new SchemaFiles(SchemaFiles.DEFAULT_LOCATION));
    }

    QuestionTypeCatalog(JdbcTemplate jdbc, SchemaFiles files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    private QuestionTypeCatalog(Map<String, QuestionTypeDef> byCode) {
        this.jdbc = null;
        this.files = null;
        this.byCode = byCode;
    }

    /** The checks and the compilation, without a database: the rows and the files in, the catalog or an {@link IllegalStateException} out. */
    static QuestionTypeCatalog build(List<TypeRow> rows, Map<String, SchemaFiles.SchemaFile> schemaFiles) {
        Set<String> seeded = new TreeSet<>();
        Map<String, QuestionTypeDef> defs = new LinkedHashMap<>();
        for (TypeRow row : rows) {
            if (!seeded.add(row.code())) {
                throw new IllegalStateException("question type " + row.code() + " is seeded twice");
            }
            InteractionKind kind;
            try {
                kind = InteractionKind.valueOf(row.kind());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("question type " + row.code() + " has an interaction kind the domain does not know: " + row.kind(), e);
            }
            SchemaFiles.SchemaFile file = schemaFiles.get(row.code());
            if (file == null) {
                throw new IllegalStateException("question type " + row.code() + " has no schema file (catalog/question-types/" + row.code() + ".schema.json)");
            }
            defs.put(row.code(), new QuestionTypeDef(row.code(), row.examCode(), row.section(), kind,
                    compile(row.code(), "content", file), compile(row.code(), "answerKey", file), row.excelTemplateId()));
        }
        for (String code : schemaFiles.keySet()) {
            if (!seeded.contains(code)) {
                throw new IllegalStateException("schema file for " + code + " has no seeded question type");
            }
        }
        return new QuestionTypeCatalog(Collections.unmodifiableMap(defs));
    }

    private static com.networknt.schema.JsonSchema compile(String code, String part, SchemaFiles.SchemaFile file) {
        try {
            return SchemaCompiler.compile(part.equals("content") ? file.content() : file.answerKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("question type " + code + ": the " + part + " schema is invalid: " + e.getMessage(), e);
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        checkInteractionKinds();
        List<TypeRow> rows = jdbc.query("""
                select q.code, q.exam_code, q.section_code, q.interaction_kind, q.excel_template_id
                  from question_types q join exam_sections s on s.exam_code = q.exam_code and s.code = q.section_code
                 order by q.exam_code, s.position, q.code
                """, (rs, n) -> new TypeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Optional.ofNullable(rs.getString(5))));
        this.byCode = build(rows, files.load()).byCode;
    }

    /** The table and the domain enum are two copies of the same list of twelve; a drift between them would be a scorer that does not exist or a kind nobody can pick. */
    private void checkInteractionKinds() {
        Map<String, Boolean> stored = new LinkedHashMap<>();
        jdbc.query("select code, auto_scored from interaction_kinds order by code", rs -> {
            stored.put(rs.getString(1), rs.getBoolean(2));
        });
        Map<String, Boolean> domain = new LinkedHashMap<>();
        Arrays.stream(InteractionKind.values()).sorted().forEach(k -> domain.put(k.name(), k.autoScored()));
        Map<String, Boolean> sortedDomain = new java.util.TreeMap<>(domain);
        if (!new java.util.TreeMap<>(stored).equals(sortedDomain)) {
            throw new IllegalStateException("interaction_kinds " + new java.util.TreeMap<>(stored) + " differs from the domain's InteractionKind " + sortedDomain);
        }
    }

    private Map<String, QuestionTypeDef> loaded() {
        Map<String, QuestionTypeDef> m = byCode;
        if (m == null) {
            throw new IllegalStateException("the question type catalog is not loaded yet");
        }
        return m;
    }

    @Override
    public QuestionTypeDef get(String code) {
        return find(code).orElseThrow(() -> new UnknownQuestionTypeException(code));
    }

    @Override
    public Optional<QuestionTypeDef> find(String code) {
        return Optional.ofNullable(code == null ? null : loaded().get(code));
    }

    @Override
    public List<QuestionTypeDef> all() {
        return List.copyOf(loaded().values());
    }

    @Override
    public List<QuestionTypeDef> forExam(String examCode) {
        List<QuestionTypeDef> out = new ArrayList<>();
        for (QuestionTypeDef d : loaded().values()) {
            if (d.examCode().equals(examCode)) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }
}
