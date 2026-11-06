INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO questions(owner_org_id,exam_code,content,answer_key,status)
 VALUES (NULL,'IELTS','{"q":"x"}','{"a":1}','DRAFT');

SET ROLE e9x_app;
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  BEGIN
    PERFORM answer_key FROM questions;
    RAISE EXCEPTION 'e9x_app must not read answer_key';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    PERFORM * FROM questions;
    RAISE EXCEPTION 'select * must be denied too';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM (SELECT content FROM questions) q) = 1, 'content readable';
END $$;
RESET ROLE;
SET ROLE e9x_grader;
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  -- grader reads answer_key (RLS still applies; english9x rows match NULL org)
  ASSERT (SELECT count(*) FROM questions WHERE answer_key IS NOT NULL) = 1, 'grader reads answer_key';
END $$;
