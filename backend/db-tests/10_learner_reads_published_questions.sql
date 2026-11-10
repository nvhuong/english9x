-- A learner's reach into questions. Until P2.3 the baseline let any learner read English9x PUBLISHED rows of `questions` directly. The version
-- tables are not readable by a learner at all (policy: the AUTHOR / REVIEWER of the exam only); the learner path is the SECURITY DEFINER function
-- e9x_question_exam_view, which returns the content side of a PUBLISHED or ARCHIVED version of English9x or of the current organisation, and
-- never a draft or an in-review version. Writes: a learner writes nothing.
INSERT INTO users(id,email) VALUES ('11111111-0000-0000-0000-000000000001','u1@x.test');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO questions(id,owner_org_id,exam_code,type_code,created_by) VALUES
 ('c1000000-0000-0000-0000-000000000001',NULL,'IELTS','IELTS_R_SHORT_ANSWER','11111111-0000-0000-0000-000000000001'),
 ('c1000000-0000-0000-0000-000000000002',NULL,'IELTS','IELTS_R_SHORT_ANSWER','11111111-0000-0000-0000-000000000001'),
 ('c1000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-000000000002','IELTS','IELTS_R_SHORT_ANSWER','11111111-0000-0000-0000-000000000001');
INSERT INTO question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) VALUES
 ('f1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001',NULL,'IELTS','IELTS_R_SHORT_ANSWER',1,'PUBLISHED','{"q":"e9-pub"}','{"a":1}','11111111-0000-0000-0000-000000000001'),
 ('f1000000-0000-0000-0000-000000000002','c1000000-0000-0000-0000-000000000002',NULL,'IELTS','IELTS_R_SHORT_ANSWER',1,'DRAFT','{"q":"e9-draft"}','{"a":2}','11111111-0000-0000-0000-000000000001'),
 ('f1000000-0000-0000-0000-000000000003','c1000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-000000000002','IELTS','IELTS_R_SHORT_ANSWER',1,'PUBLISHED','{"q":"b-pub"}','{"a":3}','11111111-0000-0000-0000-000000000001');

-- learner in the personal context: no org, not staff
SET ROLE e9x_app;
SELECT set_config('app.user_id','11111111-0000-0000-0000-000000000001',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT content FROM question_versions) q) = 0, 'a learner reads no version row';
  ASSERT (SELECT content->>'q' FROM e9x_question_exam_view('f1000000-0000-0000-0000-000000000001')) = 'e9-pub', 'but the content of the published English9x question';
  ASSERT (SELECT count(*) FROM e9x_question_exam_view('f1000000-0000-0000-0000-000000000002')) = 0, 'not of a draft';
  ASSERT (SELECT count(*) FROM e9x_question_exam_view('f1000000-0000-0000-0000-000000000003')) = 0, 'not of another organisation''s published question';
  ASSERT (SELECT count(*) FROM e9x_question_exam_view(gen_random_uuid())) = 0, 'nor of one that does not exist';
  UPDATE question_versions SET content='{"q":"hacked"}'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a learner updates nothing';
  BEGIN
    DELETE FROM question_versions;
    RAISE EXCEPTION 'nobody deletes a version';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by)
      VALUES ('c1000000-0000-0000-0000-000000000001',NULL,'IELTS','IELTS_R_SHORT_ANSWER',9,'PUBLISHED','{}','{}','11111111-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'a learner must not insert a PUBLISHED version';
  EXCEPTION WHEN insufficient_privilege OR integrity_constraint_violation THEN NULL; END;
  BEGIN
    INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (NULL,'IELTS','IELTS_R_SHORT_ANSWER','11111111-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'a learner must not insert a question';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
-- in organisation B's context the same function shows B's published question and still not a draft
SELECT set_config('app.org_id','bbbbbbbb-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM e9x_question_exam_view('f1000000-0000-0000-0000-000000000003')) = 1, 'a learner in the owner''s context reads the published question';
END $$;
