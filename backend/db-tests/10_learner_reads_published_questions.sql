INSERT INTO users(id,email) VALUES ('11111111-0000-0000-0000-000000000001','u1@x.test');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO questions(owner_org_id,exam_code,content,answer_key,status) VALUES
 (NULL,'IELTS','{"q":"e9-pub"}','{"a":1}','PUBLISHED'),
 (NULL,'IELTS','{"q":"e9-draft"}','{"a":2}','DRAFT');

-- learner in personal context: no org, not staff
SET ROLE e9x_app;
SELECT set_config('app.user_id','11111111-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT content FROM questions) q) = 1, 'learner reads exactly one english9x question';
  ASSERT (SELECT content->>'q' FROM questions) = 'e9-pub', 'learner reads the published question content';
  ASSERT (SELECT count(*) FROM (SELECT id FROM questions WHERE status='DRAFT') q) = 0, 'learner must not read draft';
  BEGIN
    PERFORM answer_key FROM questions;
    RAISE EXCEPTION 'learner must not read answer_key';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE questions SET content='{"q":"hacked"}' WHERE status='PUBLISHED'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'learner must not update published question';
  DELETE FROM questions WHERE status='PUBLISHED'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'learner must not delete published question';
  BEGIN
    INSERT INTO questions(owner_org_id,exam_code,content,answer_key,status) VALUES (NULL,'IELTS','{}','{}','PUBLISHED');
    RAISE EXCEPTION 'learner must not insert english9x question';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
