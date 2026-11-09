-- P2.1 seed: the catalog of v1 (spec 3): IELTS (Academic and General Training) and TOEIC Listening & Reading.
-- Runs as the table owner (Flyway). The application has no write privilege on any of these tables.
--
-- 2026-11-08 NOTE on score tables. Spec 3.3 and 10.2 name the conversions (raw correct answers to band / scaled score) for IELTS
-- Listening, Reading Academic, Reading General Training and TOEIC Listening and Reading, but give no values: official conversions
-- differ from one test form to the next. NONE of them is seeded: an invented conversion would show learners a wrong score. They are
-- created in the CMS (P2.5, F15) and until one is published ScoreTables.resolve for that exam and skill fails with
-- ScoreTableNotFoundException. What IS seeded below are the three aggregate functions spec 10.2 lists, which are rules of the
-- domain (Ielts.overall, Ielts.writingBand, Toeic.total) and carry no data.

-- ---- exams ---------------------------------------------------------------------------------------------------------------------
INSERT INTO exams (code, name, score_skills, variants) VALUES
  ('IELTS', 'IELTS', ARRAY['LISTENING', 'READING_AC', 'READING_GT', 'WRITING', 'SPEAKING', 'OVERALL'], ARRAY['AC', 'GT']),
  ('TOEIC', 'TOEIC Listening & Reading', ARRAY['LISTENING', 'READING', 'TOTAL'], ARRAY[]::text[])
ON CONFLICT (code) DO UPDATE
  SET name = EXCLUDED.name, score_skills = EXCLUDED.score_skills, variants = EXCLUDED.variants;

-- ---- sections (spec 3.2, 3.3; counts are what P2.6 checks a test's structure against) ----------------------------------------------
INSERT INTO exam_sections (exam_code, code, skill, position, item_count) VALUES
  ('TOEIC', 'P1', 'LISTENING', 1, 6),
  ('TOEIC', 'P2', 'LISTENING', 2, 25),
  ('TOEIC', 'P3', 'LISTENING', 3, 39),
  ('TOEIC', 'P4', 'LISTENING', 4, 30),
  ('TOEIC', 'P5', 'READING', 5, 30),
  ('TOEIC', 'P6', 'READING', 6, 16),
  ('TOEIC', 'P7', 'READING', 7, 54),
  ('IELTS', 'LISTENING', 'LISTENING', 1, 40),
  ('IELTS', 'READING', 'READING', 2, 40),
  ('IELTS', 'WRITING', 'WRITING', 3, 2),
  ('IELTS', 'SPEAKING', 'SPEAKING', 4, 3);

-- ---- interaction kinds (spec 3.1; the same twelve as com.english9x.domain.scoring.InteractionKind, a test holds them equal) ----------
INSERT INTO interaction_kinds (code, auto_scored) VALUES
  ('MCQ_SINGLE', true), ('MCQ_MULTI', true), ('GAP_TYPED', true), ('GAP_SELECT', true), ('MATCHING', true),
  ('TRUE_FALSE_NG', true), ('YES_NO_NG', true), ('LABEL_DIAGRAM', true), ('SHORT_ANSWER', true), ('ORDERING', true),
  ('ESSAY', false), ('SPEAKING_RESPONSE', false);

-- ---- question types: 8 TOEIC + 6 IELTS Listening + 11 IELTS Reading + 3 Writing + 3 Speaking = 31 -----------------------------------
-- Codes of the 8 TOEIC types, IELTS_W_*, IELTS_S_* and IELTS_R_MATCHING_HEADINGS are written in the spec. The other 16 IELTS Listening
-- and Reading codes are the spec's own names (3.3) in upper snake case under the IELTS_L_ / IELTS_R_ prefix the spec gives.
-- The JSON Schemas are classpath:catalog/question-types/<CODE>.schema.json; the application refuses to start if a type here has none
-- or a schema there has no type (QuestionTypeCatalog). Each type has an Excel template named like itself (P2.7 generates it).
INSERT INTO question_types (code, exam_code, section_code, interaction_kind, name, excel_template_id) VALUES
  ('TOEIC_P1_PHOTO',                  'TOEIC', 'P1', 'MCQ_SINGLE', 'Part 1 Photographs', 'TOEIC_P1_PHOTO'),
  ('TOEIC_P2_QUESTION_RESPONSE',      'TOEIC', 'P2', 'MCQ_SINGLE', 'Part 2 Question-Response', 'TOEIC_P2_QUESTION_RESPONSE'),
  ('TOEIC_P3_CONVERSATION',           'TOEIC', 'P3', 'MCQ_SINGLE', 'Part 3 Conversations', 'TOEIC_P3_CONVERSATION'),
  ('TOEIC_P4_TALK',                   'TOEIC', 'P4', 'MCQ_SINGLE', 'Part 4 Talks', 'TOEIC_P4_TALK'),
  ('TOEIC_P5_INCOMPLETE_SENTENCE',    'TOEIC', 'P5', 'MCQ_SINGLE', 'Part 5 Incomplete Sentences', 'TOEIC_P5_INCOMPLETE_SENTENCE'),
  ('TOEIC_P6_TEXT_COMPLETION',        'TOEIC', 'P6', 'GAP_SELECT', 'Part 6 Text Completion', 'TOEIC_P6_TEXT_COMPLETION'),
  ('TOEIC_P7_SINGLE_PASSAGE',         'TOEIC', 'P7', 'MCQ_SINGLE', 'Part 7 Single Passages', 'TOEIC_P7_SINGLE_PASSAGE'),
  ('TOEIC_P7_MULTIPLE_PASSAGES',      'TOEIC', 'P7', 'MCQ_SINGLE', 'Part 7 Multiple Passages', 'TOEIC_P7_MULTIPLE_PASSAGES'),

  ('IELTS_L_MULTIPLE_CHOICE',         'IELTS', 'LISTENING', 'MCQ_MULTI', 'Listening: multiple choice', 'IELTS_L_MULTIPLE_CHOICE'),
  ('IELTS_L_MATCHING',                'IELTS', 'LISTENING', 'MATCHING', 'Listening: matching', 'IELTS_L_MATCHING'),
  ('IELTS_L_PLAN_MAP_DIAGRAM_LABELLING', 'IELTS', 'LISTENING', 'LABEL_DIAGRAM', 'Listening: plan, map, diagram labelling', 'IELTS_L_PLAN_MAP_DIAGRAM_LABELLING'),
  ('IELTS_L_FORM_NOTE_TABLE_FLOW_CHART_SUMMARY_COMPLETION', 'IELTS', 'LISTENING', 'GAP_TYPED',
     'Listening: form, note, table, flow-chart, summary completion', 'IELTS_L_FORM_NOTE_TABLE_FLOW_CHART_SUMMARY_COMPLETION'),
  ('IELTS_L_SENTENCE_COMPLETION',     'IELTS', 'LISTENING', 'GAP_TYPED', 'Listening: sentence completion', 'IELTS_L_SENTENCE_COMPLETION'),
  ('IELTS_L_SHORT_ANSWER',            'IELTS', 'LISTENING', 'SHORT_ANSWER', 'Listening: short answer', 'IELTS_L_SHORT_ANSWER'),

  ('IELTS_R_MULTIPLE_CHOICE',         'IELTS', 'READING', 'MCQ_MULTI', 'Reading: multiple choice', 'IELTS_R_MULTIPLE_CHOICE'),
  ('IELTS_R_IDENTIFYING_INFORMATION', 'IELTS', 'READING', 'TRUE_FALSE_NG', 'Reading: identifying information (True/False/Not Given)', 'IELTS_R_IDENTIFYING_INFORMATION'),
  ('IELTS_R_IDENTIFYING_WRITERS_VIEWS', 'IELTS', 'READING', 'YES_NO_NG', 'Reading: identifying writer''s views (Yes/No/Not Given)', 'IELTS_R_IDENTIFYING_WRITERS_VIEWS'),
  ('IELTS_R_MATCHING_INFORMATION',    'IELTS', 'READING', 'MATCHING', 'Reading: matching information', 'IELTS_R_MATCHING_INFORMATION'),
  ('IELTS_R_MATCHING_HEADINGS',       'IELTS', 'READING', 'MATCHING', 'Reading: matching headings', 'IELTS_R_MATCHING_HEADINGS'),
  ('IELTS_R_MATCHING_FEATURES',       'IELTS', 'READING', 'MATCHING', 'Reading: matching features', 'IELTS_R_MATCHING_FEATURES'),
  ('IELTS_R_MATCHING_SENTENCE_ENDINGS', 'IELTS', 'READING', 'MATCHING', 'Reading: matching sentence endings', 'IELTS_R_MATCHING_SENTENCE_ENDINGS'),
  ('IELTS_R_SENTENCE_COMPLETION',     'IELTS', 'READING', 'GAP_TYPED', 'Reading: sentence completion', 'IELTS_R_SENTENCE_COMPLETION'),
  ('IELTS_R_SUMMARY_NOTE_TABLE_FLOW_CHART_COMPLETION', 'IELTS', 'READING', 'GAP_TYPED',
     'Reading: summary, note, table, flow-chart completion', 'IELTS_R_SUMMARY_NOTE_TABLE_FLOW_CHART_COMPLETION'),
  ('IELTS_R_DIAGRAM_LABEL_COMPLETION', 'IELTS', 'READING', 'LABEL_DIAGRAM', 'Reading: diagram label completion', 'IELTS_R_DIAGRAM_LABEL_COMPLETION'),
  ('IELTS_R_SHORT_ANSWER',            'IELTS', 'READING', 'SHORT_ANSWER', 'Reading: short answer', 'IELTS_R_SHORT_ANSWER'),

  ('IELTS_W_AC_TASK1',                'IELTS', 'WRITING', 'ESSAY', 'Writing Academic Task 1', 'IELTS_W_AC_TASK1'),
  ('IELTS_W_GT_TASK1',                'IELTS', 'WRITING', 'ESSAY', 'Writing General Training Task 1', 'IELTS_W_GT_TASK1'),
  ('IELTS_W_TASK2',                   'IELTS', 'WRITING', 'ESSAY', 'Writing Task 2', 'IELTS_W_TASK2'),
  ('IELTS_S_PART1',                   'IELTS', 'SPEAKING', 'SPEAKING_RESPONSE', 'Speaking Part 1', 'IELTS_S_PART1'),
  ('IELTS_S_PART2',                   'IELTS', 'SPEAKING', 'SPEAKING_RESPONSE', 'Speaking Part 2', 'IELTS_S_PART2'),
  ('IELTS_S_PART3',                   'IELTS', 'SPEAKING', 'SPEAKING_RESPONSE', 'Speaking Part 3', 'IELTS_S_PART3');

-- ---- aggregate score functions (spec 10.2): published version 1, no data ---------------------------------------------------------------
WITH t AS (
  INSERT INTO score_tables (exam_code, skill, scope, function) VALUES
    ('IELTS', 'WRITING', 'EXAM', 'IELTS_WRITING_WEIGHTED'),
    ('IELTS', 'OVERALL', 'EXAM', 'IELTS_OVERALL'),
    ('TOEIC', 'TOTAL',   'EXAM', 'SUM')
  RETURNING id, exam_code
)
INSERT INTO score_table_versions (score_table_id, exam_code, version_no, status, entries)
SELECT id, exam_code, 1, 'PUBLISHED', '[]'::jsonb FROM t;
