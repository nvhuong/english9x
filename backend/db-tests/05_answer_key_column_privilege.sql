-- The answer key is never reachable by a learner (Global Constraint, Review Focus). Until P2.3 the baseline `questions.answer_key` had no SELECT
-- grant for e9x_app, which also meant the author could not read their own key. P2.3 (ruling of the P1 final review) moved the key to
-- question_versions: the AUTHOR and REVIEWER of the exam read it, in the version's own context, through a policy of their own; every other role
-- sees no row of that table at all, so neither answer_key nor explanation can be selected whatever the column list says. Extended to the group
-- versions' solution side. The learner's way in (e9x_question_exam_view) returns the content side only.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER');
INSERT INTO users(id,email) VALUES
 ('d5000000-0000-0000-0000-000000000001','author@x.test'),
 ('d5000000-0000-0000-0000-000000000002','learner@x.test'),
 ('d5000000-0000-0000-0000-000000000003','toeic-author@x.test'),
 ('d5000000-0000-0000-0000-000000000004','reviewer@x.test');
INSERT INTO org_memberships(org_id,user_id) SELECT 'aaaaaaaa-0000-0000-0000-000000000001', id FROM users;
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('d5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','IELTS','AUTHOR'),
 ('d5000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001',NULL,'LEARNER'),
 ('d5000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000001','TOEIC','AUTHOR'),
 ('d5000000-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000001','IELTS','REVIEWER');
INSERT INTO questions(id,owner_org_id,exam_code,type_code,created_by) VALUES
 ('c5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','IELTS','IELTS_R_SHORT_ANSWER','d5000000-0000-0000-0000-000000000001'),
 ('c5000000-0000-0000-0000-000000000002',NULL,'IELTS','IELTS_R_SHORT_ANSWER','d5000000-0000-0000-0000-000000000001');
INSERT INTO question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,explanation,created_by) VALUES
 ('f5000000-0000-0000-0000-000000000001','c5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','IELTS','IELTS_R_SHORT_ANSWER',1,'PUBLISHED','{"stem":"s"}','{"slots":[["SECRET-A"]],"transcript":"SECRET-T"}','SECRET-X','d5000000-0000-0000-0000-000000000001'),
 ('f5000000-0000-0000-0000-000000000002','c5000000-0000-0000-0000-000000000002',NULL,'IELTS','IELTS_R_SHORT_ANSWER',1,'PUBLISHED','{"stem":"e9"}','{"slots":[["SECRET-E"]]}','SECRET-X','d5000000-0000-0000-0000-000000000001');

SET ROLE e9x_app;
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);

-- a LEARNER: not one row of any version table, so no answer_key, no explanation, by name or by *
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT answer_key FROM question_versions) q) = 0, 'a learner selects no answer key';
  ASSERT (SELECT count(*) FROM (SELECT * FROM question_versions) q) = 0, 'select * returns no row either';
  ASSERT (SELECT count(*) FROM (SELECT explanation FROM question_versions) q) = 0, 'nor an explanation';
  ASSERT (SELECT count(*) FROM (SELECT * FROM question_group_versions) q) = 0, 'nor the solution side of a group';
  ASSERT (SELECT count(*) FROM (SELECT * FROM questions) q) = 0, 'nor a header';
  -- but the content side of a published version, through the narrow function, without any solution column
  ASSERT (SELECT count(*) FROM e9x_question_exam_view('f5000000-0000-0000-0000-000000000001')) = 1, 'the learner reads the content of a published question';
  ASSERT (SELECT count(*) FROM e9x_question_exam_view('f5000000-0000-0000-0000-000000000002')) = 1, 'and of an English9x one';
  ASSERT NOT EXISTS (SELECT 1 FROM e9x_question_exam_view('f5000000-0000-0000-0000-000000000001') f, jsonb_object_keys(to_jsonb(f)) k
                      WHERE k ~* 'answer|explanation|transcript|solution'), 'the view has no solution-side column';
  ASSERT NOT (to_jsonb((SELECT f FROM e9x_question_exam_view('f5000000-0000-0000-0000-000000000001') f))::text ~ 'SECRET'), 'and no secret value';
END $$;

-- another exam's author: nothing
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT answer_key FROM question_versions) q) = 0, 'the author of another exam selects no answer key';
END $$;

-- the author and the reviewer of the exam, in the context: their own key
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000001',false);
DO $$
BEGIN
  ASSERT (SELECT answer_key->'slots'->0->>0 FROM question_versions) = 'SECRET-A', 'the author reads the key of their exam, and only of this context';
  ASSERT (SELECT explanation FROM question_versions) = 'SECRET-X', 'and the explanation';
END $$;
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000004',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT answer_key FROM question_versions) q) = 1, 'the reviewer reads the key under review';
END $$;
-- the author in another context (the English9x flag without a grant, or no organisation): nothing
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT answer_key FROM question_versions) q) = 0, 'the same person in the personal context reads no key';
END $$;
RESET ROLE;

-- the grader scores against PUBLISHED and ARCHIVED versions of English9x and its current organisation, never a draft
INSERT INTO question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) VALUES
 ('f5000000-0000-0000-0000-000000000003','c5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','IELTS','IELTS_R_SHORT_ANSWER',2,'DRAFT','{"stem":"d"}','{"slots":[["DRAFTKEY"]]}','d5000000-0000-0000-0000-000000000001');
SET ROLE e9x_grader;
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_versions WHERE answer_key::text LIKE '%SECRET-%') = 2, 'the grader reads published keys of English9x and of the context';
  ASSERT (SELECT count(*) FROM question_versions WHERE status = 'DRAFT') = 0, 'but no draft';
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'questions' AND column_name IN ('answer_key', 'content', 'status')),
         'the header holds no content, no key and no status';
END $$;
