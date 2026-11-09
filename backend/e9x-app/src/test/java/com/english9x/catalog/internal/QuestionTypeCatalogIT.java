package com.english9x.catalog.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.ExamInfo;
import com.english9x.catalog.api.Exams;
import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.domain.scoring.InteractionKind;
import com.english9x.support.TestPostgres;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The catalog as the application starts it: the seeded rows and the schema files agree, and a disagreement stops the start-up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QuestionTypeCatalogIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired QuestionTypes types;
    @Autowired Exams exams;
    @Autowired JdbcTemplate appJdbc;
    final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    @Test
    void theApplicationStartedWithAll31Types() {
        assertEquals(31, types.all().size());
        assertEquals(8, types.forExam("TOEIC").size());
        assertEquals(23, types.forExam("IELTS").size());
        QuestionTypeDef p1 = types.get("TOEIC_P1_PHOTO");
        assertEquals("TOEIC", p1.examCode());
        assertEquals("P1", p1.section());
        assertEquals(InteractionKind.MCQ_SINGLE, p1.kind());
        assertEquals(Optional.of("TOEIC_P1_PHOTO"), p1.excelTemplateId());
        assertEquals(InteractionKind.MATCHING, types.get("IELTS_R_MATCHING_HEADINGS").kind());
        assertEquals(InteractionKind.SPEAKING_RESPONSE, types.get("IELTS_S_PART3").kind());
        assertTrue(types.all().stream().map(QuestionTypeDef::kind).distinct().count() >= 10, "most of the twelve kinds are used");
    }

    @Test
    void examsKnowTheirSectionsAndTheirScoreSkills() {
        assertEquals(List.of("IELTS", "TOEIC"), exams.all().stream().map(ExamInfo::code).sorted().toList());
        ExamInfo toeic = exams.find("TOEIC").orElseThrow();
        assertEquals(List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7"), toeic.sections().stream().map(s -> s.code()).toList());
        assertEquals(List.of(6, 25, 39, 30, 30, 16, 54), toeic.sections().stream().map(s -> s.itemCount()).toList());
        ExamInfo ielts = exams.find("IELTS").orElseThrow();
        assertEquals(List.of("LISTENING", "READING", "WRITING", "SPEAKING"), ielts.sections().stream().map(s -> s.code()).toList());
        assertEquals(List.of("AC", "GT"), ielts.variants());
        assertTrue(ielts.scoreSkills().containsAll(List.of("READING_AC", "READING_GT")));
        assertTrue(exams.exists("IELTS") && !exams.exists("NOPE") && exams.find("NOPE").isEmpty());
    }

    @Test
    void theInteractionKindsTableIsTheDomainEnum() {
        List<String> stored = su.queryForList("select code from interaction_kinds order by code", String.class);
        assertEquals(Arrays.stream(InteractionKind.values()).map(Enum::name).sorted().toList(), stored);
    }

    private QuestionTypeCatalog catalogOver(String schemaLocation) {
        return new QuestionTypeCatalog(appJdbc, new SchemaFiles(schemaLocation));
    }

    @Test
    void aSeededTypeWithoutASchemaFileStopsTheStartUp() {
        // a directory that holds no schema at all
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> catalogOver("classpath*:catalog-empty/*.schema.json").afterSingletonsInstantiated());
        assertTrue(e.getMessage().contains("has no schema file"), e.getMessage());
    }

    @Test
    void aSchemaFileWithoutASeededTypeStopsTheStartUp() {
        // the real files plus a stray one
        su.update("delete from question_types where code = 'IELTS_S_PART3'");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new QuestionTypeCatalog(su, new SchemaFiles(SchemaFiles.DEFAULT_LOCATION)).afterSingletonsInstantiated());
            assertTrue(e.getMessage().contains("IELTS_S_PART3"), e.getMessage());
        } finally {
            su.update("insert into question_types (code, exam_code, section_code, interaction_kind, name, excel_template_id) "
                    + "values ('IELTS_S_PART3','IELTS','SPEAKING','SPEAKING_RESPONSE','Speaking Part 3','IELTS_S_PART3')");
        }
    }

    @Test
    void aDriftBetweenTheKindsTableAndTheDomainStopsTheStartUp() {
        su.update("update interaction_kinds set auto_scored = not auto_scored where code = 'ESSAY'");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new QuestionTypeCatalog(su, new SchemaFiles(SchemaFiles.DEFAULT_LOCATION)).afterSingletonsInstantiated());
            assertTrue(e.getMessage().contains("interaction_kinds"), e.getMessage());
        } finally {
            su.update("update interaction_kinds set auto_scored = false where code = 'ESSAY'");
        }
    }
}
