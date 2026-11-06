INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO questions(owner_org_id,exam_code,content,answer_key,status) VALUES
 (NULL,'IELTS','{"q":"e9-pub"}','{"a":1}','PUBLISHED'),
 (NULL,'IELTS','{"q":"e9-draft"}','{"a":2}','DRAFT'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','{"q":"b-pub"}','{"a":3}','PUBLISHED'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','{"q":"b-draft"}','{"a":4}','DRAFT');

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
  ASSERT (SELECT count(*) FROM (SELECT content FROM questions WHERE owner_org_id IS NULL) q) = 2, 'staff reads english9x content';
END $$;
RESET ROLE;

-- grader runs with the attempt's org context, NOT the staff flag
SET ROLE e9x_grader;
SELECT set_config('app.e9x_staff','',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
BEGIN
  ASSERT (SELECT answer_key->>'a' FROM questions WHERE content->>'q' = 'e9-pub') = '1', 'grader reads published english9x answer_key';
  ASSERT (SELECT count(*) FROM questions WHERE content->>'q' = 'e9-draft') = 0, 'grader must not read english9x draft';
  ASSERT (SELECT count(*) FROM questions WHERE content->>'q' = 'b-pub') = 1, 'grader reads published key of a shared question';
  ASSERT (SELECT count(*) FROM questions WHERE content->>'q' = 'b-draft') = 0, 'grader must not read other org draft';
END $$;
