-- P2.1 catalog tables.
--  * The reference tables (exams, exam_sections, interaction_kinds, question_types) are read-only for the application and the grader.
--  * Score tables, grading skills and grading policies are owner-scoped: an org's rows never reach another org; a draft is read by the
--    AUTHOR / REVIEWER of THAT exam in the owner's context only; a learner reads published score tables and policies, never a skill;
--    nothing here has an "any staff" clause (a TOEIC author is no author of an IELTS table).
--  * Writes are by role, exam and context; versions obey the lifecycle (an author edits drafts, a reviewer decides, a published row is frozen).
--  * The seed holds 31 question types, no invented score conversion, and the three aggregate functions.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('c1000000-0000-0000-0000-000000000001','author-a-ielts@x.test'),
 ('c1000000-0000-0000-0000-000000000002','reviewer-a-ielts@x.test'),
 ('c1000000-0000-0000-0000-000000000003','learner-a@x.test'),
 ('c1000000-0000-0000-0000-000000000004','author-b-ielts@x.test'),
 ('c1000000-0000-0000-0000-000000000005','author-e9-ielts@x.test'),
 ('c1000000-0000-0000-0000-000000000006','reviewer-e9-ielts@x.test'),
 ('c1000000-0000-0000-0000-000000000007','sysadmin@x.test'),
 ('c1000000-0000-0000-0000-000000000008','orgadmin-a@x.test'),
 ('c1000000-0000-0000-0000-000000000009','teacher-a-ielts@x.test'),
 ('c1000000-0000-0000-0000-00000000000a','author-a-toeic@x.test'),
 ('c1000000-0000-0000-0000-00000000000b','teacher-e9-ielts@x.test'),
 ('c1000000-0000-0000-0000-00000000000c','orgadmin-c-enterprise@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER'),
 ('cccccccc-0000-0000-0000-00000000000c','Enterprise C','ENTERPRISE');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-000000000008'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-000000000009'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-00000000000a'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','c1000000-0000-0000-0000-000000000004'),
 ('cccccccc-0000-0000-0000-00000000000c','c1000000-0000-0000-0000-00000000000c');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('c1000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('c1000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('c1000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('c1000000-0000-0000-0000-000000000004','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AUTHOR'),
 ('c1000000-0000-0000-0000-000000000005',NULL,'IELTS','AUTHOR'),
 ('c1000000-0000-0000-0000-000000000006',NULL,'IELTS','REVIEWER'),
 ('c1000000-0000-0000-0000-000000000007',NULL,NULL,'SYS_ADMIN'),
 ('c1000000-0000-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('c1000000-0000-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','TEACHER'),
 ('c1000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','AUTHOR'),
 ('c1000000-0000-0000-0000-00000000000b',NULL,'IELTS','TEACHER'),
 ('c1000000-0000-0000-0000-00000000000c','cccccccc-0000-0000-0000-00000000000c',NULL,'ORG_ADMIN');

-- ---- the seed (as the owner) ---------------------------------------------------------------------------------------------------
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_types) = 31, '31 question types are seeded';
  ASSERT (SELECT count(*) FROM question_types WHERE exam_code = 'TOEIC') = 8, '8 TOEIC types';
  ASSERT (SELECT count(*) FROM question_types WHERE exam_code = 'IELTS') = 23, '23 IELTS types';
  ASSERT (SELECT count(*) FROM interaction_kinds) = 12, '12 interaction kinds';
  ASSERT (SELECT count(*) FROM exam_sections WHERE exam_code = 'TOEIC') = 7 AND (SELECT sum(item_count) FROM exam_sections WHERE exam_code = 'TOEIC') = 200,
         'TOEIC: 7 parts, 200 questions';
  ASSERT (SELECT count(*) FROM exam_sections WHERE exam_code = 'IELTS') = 4, 'IELTS: 4 sections';
  -- spec 10.2 gives no values: the only seeded score tables are the three functions, and they carry no data
  ASSERT (SELECT count(*) FROM score_tables) = 3 AND (SELECT count(*) FROM score_tables WHERE function = 'TABLE') = 0, 'no invented conversion table is seeded';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE entries <> '[]'::jsonb) = 0, 'and no seeded version holds data';
  ASSERT (SELECT count(*) FROM grading_policies) = 0 AND (SELECT count(*) FROM grading_skills) = 0, 'no policy or skill is seeded';
END $$;

-- ---- TEST scope is refused until P2.6 (I1/I2 of the P2.1 review) ---------------------------------------------------------------------
-- No TEST-scope row can be inserted by anyone, the owner of the tables included: `tests` does not exist yet, so nothing says whose test
-- an id is. P2.6 lifts both constraints together with the foreign key, the owner match and the administrator-only writers.
DO $$
BEGIN
  BEGIN
    INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (NULL,'IELTS','LISTENING','TEST',gen_random_uuid());
    ASSERT false, 'the owner of the table inserted a TEST score table';
  EXCEPTION WHEN check_violation THEN ASSERT SQLERRM LIKE '%score_tables_test_scope_refused_ck%', SQLERRM; END;
  BEGIN
    INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode) VALUES (NULL,'IELTS','TEST',gen_random_uuid(),'AI');
    ASSERT false, 'the owner of the table inserted a TEST grading policy';
  EXCEPTION WHEN check_violation THEN ASSERT SQLERRM LIKE '%grading_policies_test_scope_refused_ck%', SQLERRM; END;
END $$;
-- The fixtures below need TEST rows to exercise the policies and keys the way P2.6 will: drop the two constraints, insert, and put them
-- back NOT VALID (the fixtures predate them; every later insert is checked).
ALTER TABLE score_tables DROP CONSTRAINT score_tables_test_scope_refused_ck;
ALTER TABLE grading_policies DROP CONSTRAINT grading_policies_test_scope_refused_ck;

-- P2.5 (V202611110005) allows one open version per table and per skill. These fixtures hold several open versions of one table on purpose
-- (a draft, one under review, the author's submission), so the two indexes are dropped here; 42_scoring_guards.sql tests them. The decision
-- trigger stays: the version under review below has its open review, the way the workflow service always has it.
DROP INDEX score_table_versions_one_open_uq;
DROP INDEX grading_skill_versions_one_open_uq;

-- fixtures (as the owner): two tests of org A, one of org B, an English9x default, skills and policies
INSERT INTO score_tables(id,owner_org_id,exam_code,skill,scope,test_id) VALUES
 ('51000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','LISTENING','TEST','71000000-0000-0000-0000-00000000000a'),
 ('51000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','LISTENING','TEST','71000000-0000-0000-0000-00000000000b');
INSERT INTO score_tables(id,owner_org_id,exam_code,skill,scope) VALUES
 ('51000000-0000-0000-0000-0000000000e9',NULL,'IELTS','LISTENING','EXAM');
INSERT INTO score_table_versions(id,score_table_id,owner_org_id,exam_code,version_no,status,entries) VALUES
 ('52000000-0000-0000-0000-0000000a0001','51000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'PUBLISHED','[{"raw":0,"scaled":0}]'),
 ('52000000-0000-0000-0000-0000000a0002','51000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',2,'DRAFT','[{"raw":0,"scaled":1}]'),
 ('52000000-0000-0000-0000-0000000a0003','51000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',3,'IN_REVIEW','[{"raw":0,"scaled":2}]'),
 ('52000000-0000-0000-0000-0000000b0001','51000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS',1,'PUBLISHED','[{"raw":0,"scaled":0}]'),
 ('52000000-0000-0000-0000-0000000b0002','51000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS',2,'DRAFT','[{"raw":0,"scaled":1}]'),
 ('52000000-0000-0000-0000-000000e90001','51000000-0000-0000-0000-0000000000e9',NULL,'IELTS',1,'PUBLISHED','[{"raw":0,"scaled":0}]'),
 ('52000000-0000-0000-0000-000000e90002','51000000-0000-0000-0000-0000000000e9',NULL,'IELTS',2,'DRAFT','[{"raw":0,"scaled":1}]');
INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('score_table','52000000-0000-0000-0000-0000000a0003','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','c1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001');
INSERT INTO grading_skills(id,owner_org_id,exam_code,question_type_code) VALUES
 ('61000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_W_TASK2'),
 ('61000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','IELTS_W_TASK2'),
 ('61000000-0000-0000-0000-0000000000e9',NULL,'IELTS','IELTS_W_TASK2');
INSERT INTO grading_skill_versions(id,grading_skill_id,owner_org_id,exam_code,version_no,status,rubric) VALUES
 ('62000000-0000-0000-0000-0000000a0001','61000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'PUBLISHED','rubric A'),
 ('62000000-0000-0000-0000-0000000a0002','61000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',2,'DRAFT','rubric A draft'),
 ('62000000-0000-0000-0000-0000000b0001','61000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS',1,'PUBLISHED','rubric B'),
 ('62000000-0000-0000-0000-000000e90001','61000000-0000-0000-0000-0000000000e9',NULL,'IELTS',1,'PUBLISHED','rubric E9'),
 ('62000000-0000-0000-0000-000000e90002','61000000-0000-0000-0000-0000000000e9',NULL,'IELTS',2,'DRAFT','rubric E9 draft');
-- an author edits only a version they created (V202611110005): the drafts of org A are the author's
UPDATE score_table_versions SET created_by = 'c1000000-0000-0000-0000-000000000001' WHERE id = '52000000-0000-0000-0000-0000000a0002';
UPDATE grading_skill_versions SET created_by = 'c1000000-0000-0000-0000-000000000001' WHERE id = '62000000-0000-0000-0000-0000000a0002';
UPDATE score_table_versions SET created_by = 'c1000000-0000-0000-0000-000000000005' WHERE id = '52000000-0000-0000-0000-000000e90002';
UPDATE grading_skill_versions SET created_by = 'c1000000-0000-0000-0000-000000000005' WHERE id = '62000000-0000-0000-0000-000000e90002';
INSERT INTO grading_policies(id,owner_org_id,exam_code,scope,test_id,mode) VALUES
 ('63000000-0000-0000-0000-0000000000e9',NULL,'IELTS','EXAM',NULL,'AI'),
 ('63000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','ORG',NULL,'TEACHER'),
 ('63000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','ORG',NULL,'BOTH'),
 ('63000000-0000-0000-0000-00000000007a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','TEST','71000000-0000-0000-0000-00000000000a','BOTH');

-- helpers (created as the owner, run as whoever calls them): the number of rows a statement changed, or the SQLSTATE it failed with
CREATE FUNCTION public.t_msg(stmt text) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE stmt;
  RETURN 'ok';
EXCEPTION WHEN OTHERS THEN RETURN SQLSTATE || ' ' || SQLERRM;
END $$;
GRANT EXECUTE ON FUNCTION public.t_msg(text) TO e9x_app, e9x_grader;
CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app, e9x_grader;

-- a second table of org A (the author's flow below adds versions to it)
INSERT INTO score_tables(id,owner_org_id,exam_code,skill,scope,test_id) VALUES
 ('51000000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','READING_AC','TEST','71000000-0000-0000-0000-0000000000a2');

-- keys carry the owner: one owner's row never blocks another's (no 23505 on a row the caller cannot see), the same owner's still does
DO $$
BEGIN
  -- the very test id of org A's override, used by org B and by English9x for the same exam and skill
  INSERT INTO score_tables(id,owner_org_id,exam_code,skill,scope,test_id) VALUES
   ('51000000-0000-0000-0000-0000000000b1','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','LISTENING','TEST','71000000-0000-0000-0000-00000000000a'),
   ('51000000-0000-0000-0000-0000000000e1',NULL,'IELTS','LISTENING','TEST','71000000-0000-0000-0000-00000000000a');
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''LISTENING'',''TEST'',''71000000-0000-0000-0000-00000000000a'')') LIKE '23505%',
         'the same owner, test and skill twice is refused';
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (NULL,''IELTS'',''LISTENING'',''TEST'',''71000000-0000-0000-0000-00000000000a'')') LIKE '23505%',
         'also for English9x (NULL owner counts as a value)';
  DELETE FROM score_tables WHERE id IN ('51000000-0000-0000-0000-0000000000b1','51000000-0000-0000-0000-0000000000e1');
  INSERT INTO grading_policies(id,owner_org_id,exam_code,scope,test_id,mode) VALUES
   ('63000000-0000-0000-0000-0000000000b1','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','TEST','71000000-0000-0000-0000-00000000000a','AI'),
   ('63000000-0000-0000-0000-0000000000e1',NULL,'IELTS','TEST','71000000-0000-0000-0000-00000000000a','AI');
  ASSERT public.t_msg('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''TEST'',''71000000-0000-0000-0000-00000000000a'',''BOTH'')') LIKE '23505%',
         'the same owner and test twice is refused for a policy';
  DELETE FROM grading_policies WHERE id IN ('63000000-0000-0000-0000-0000000000b1','63000000-0000-0000-0000-0000000000e1');
END $$;
-- the refusal is back for everything inserted from here on
ALTER TABLE score_tables ADD CONSTRAINT score_tables_test_scope_refused_ck CHECK (scope <> 'TEST') NOT VALID;
ALTER TABLE grading_policies ADD CONSTRAINT grading_policies_test_scope_refused_ck CHECK (scope <> 'TEST') NOT VALID;

-- ---- reference data is read-only for the application and the grader ---------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000007',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['exams','exam_sections','interaction_kinds','question_types'] LOOP
    ASSERT (SELECT count(*) FROM pg_class WHERE relname = t) = 1;
    ASSERT has_table_privilege('e9x_app', t, 'SELECT'), t || ' is readable';
    ASSERT NOT has_table_privilege('e9x_app', t, 'INSERT') AND NOT has_table_privilege('e9x_app', t, 'UPDATE')
           AND NOT has_table_privilege('e9x_app', t, 'DELETE'), t || ': the application cannot write reference data';
    ASSERT NOT has_table_privilege('e9x_grader', t, 'INSERT') AND NOT has_table_privilege('e9x_grader', t, 'UPDATE')
           AND NOT has_table_privilege('e9x_grader', t, 'DELETE'), t || ': nor can the grader';
  END LOOP;
  ASSERT public.t_attempt('INSERT INTO exams(code,name) VALUES (''X'',''X'')') = 'err:42501', 'even SYS_ADMIN cannot add an exam through the application';
  ASSERT public.t_attempt('UPDATE exams SET name = ''hacked''') = 'err:42501', 'or rename one';
  ASSERT public.t_attempt('DELETE FROM question_types') = 'err:42501', 'or delete a type';
  ASSERT public.t_attempt('UPDATE question_types SET interaction_kind = ''ESSAY''') = 'err:42501';
  ASSERT (SELECT count(*) FROM question_types) = 31, 'every caller reads the catalog';
  -- P1 final review M4: no DELETE grant without a policy
  ASSERT NOT has_table_privilege('e9x_app', 'organizations', 'DELETE') AND NOT has_table_privilege('e9x_app', 'entitlements', 'DELETE')
         AND NOT has_table_privilege('e9x_app', 'exam_attempts', 'DELETE'), 'no unused DELETE grants';
END $$;

-- ---- a learner of org A ------------------------------------------------------------------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000003',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_tables) = 6, 'learner A: the 3 functions, the English9x default and org A''s own two tables; not org B''s';
  ASSERT (SELECT count(*) FROM score_tables WHERE owner_org_id = 'bbbbbbbb-0000-0000-0000-00000000000b') = 0, 'nothing of org B';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 1
         AND (SELECT status FROM score_table_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 'PUBLISHED', 'only the published version of org A';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id IS NULL AND entries <> '[]') = 1, 'and the published English9x one';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE status NOT IN ('PUBLISHED','ARCHIVED')) = 0, 'no draft, no version under review';
  ASSERT (SELECT count(*) FROM grading_skills) = 0 AND (SELECT count(*) FROM grading_skill_versions) = 0, 'a learner reads no grading skill';
  ASSERT (SELECT count(*) FROM grading_policies) = 3, 'learner A reads the exam default, org A''s policy and org A''s test policy';
  ASSERT (SELECT count(*) FROM grading_policies WHERE id = '63000000-0000-0000-0000-00000000000b') = 0, 'not org B''s policy';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''READING_AC'',''TEST'',gen_random_uuid())') = 'err:42501', 'a learner writes no score table';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000003'')') = 'err:42501', 'nor a policy';
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''AI''') = 'rows:0', 'nor change one (no policy lets a learner see a row to update)';
END $$;
-- the same learner in the personal context sees English9x's published rows only
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_tables) = 4, 'personal context: the English9x tables only';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id IS NOT NULL) = 0, 'no organisation''s version';
  ASSERT (SELECT count(*) FROM grading_policies) = 1, 'the exam default policy only';
END $$;

-- ---- org B's author, in org B: sees B, never A -----------------------------------------------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000004',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 0, 'org B sees nothing of org A';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id = 'bbbbbbbb-0000-0000-0000-00000000000b') = 2, 'its own published and draft versions';
  ASSERT (SELECT count(*) FROM grading_skill_versions) = 1 AND (SELECT count(*) FROM grading_skills) = 1, 'its own skill, and none of org A or English9x';
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,created_by) VALUES (''51000000-0000-0000-0000-00000000000a'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',9,''c1000000-0000-0000-0000-000000000004'')') = 'err:42501', 'it cannot add a version to org A''s table';
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,created_by) VALUES (''51000000-0000-0000-0000-00000000000a'',''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',9,''c1000000-0000-0000-0000-000000000004'')') = 'err:42501', 'nor attach a version of its own to org A''s table (owner must equal the parent''s)';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[]'' WHERE owner_org_id = ''aaaaaaaa-0000-0000-0000-00000000000a''') = 'rows:0', 'nor edit org A''s draft';
  ASSERT public.t_attempt('DELETE FROM score_table_versions WHERE owner_org_id = ''aaaaaaaa-0000-0000-0000-00000000000a''') = 'rows:0', 'nor delete it';
  -- TOEIC has no policy of org B yet, so no unique key can answer first: only the missing role can refuse this
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''TOEIC'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000004'')') = 'err:42501',
         'an author is not an ORG_ADMIN: no organisation policy';
END $$;

-- ---- author of IELTS in org A ---------------------------------------------------------------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 3, 'the author reads all three versions of org A (published, draft, in review)';
  ASSERT (SELECT count(*) FROM grading_skill_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 2, 'and both skill versions';
  ASSERT (SELECT count(*) FROM grading_skill_versions WHERE owner_org_id IS NULL) = 0, 'not English9x''s skill from inside the organisation';
  -- a new TEST table is refused whoever asks (after the role check: a 23514 here means RLS let the author through)
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''READING_GT'',''TEST'',gen_random_uuid())') LIKE '23514%score_tables_test_scope_refused_ck%',
         'the author of org A cannot create a TEST table until P2.6';
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''NO_SUCH_SKILL'',''TEST'',gen_random_uuid())') LIKE '23514%keeps no score%', 'a skill the exam keeps no score for is refused (the skill trigger, not the scope CHECK)';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''READING'',''TEST'',gen_random_uuid())') = 'err:42501', 'not for TOEIC: the grant is for IELTS';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (NULL,''IELTS'',''READING_AC'',''TEST'',gen_random_uuid())') = 'err:42501', 'not for English9x: this author is a member of org A';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''READING_GT'',''EXAM'')') = 'err:23514', 'an exam default is English9x''s: an organisation''s EXAM-scope table is refused by the constraint';
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,entries,created_by) VALUES (''51000000-0000-0000-0000-0000000000a2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',1,''[{"raw":0,"scaled":0}]'',''c1000000-0000-0000-0000-000000000001'')') = 'rows:1', 'a DRAFT version';
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,created_by) VALUES (''51000000-0000-0000-0000-0000000000a2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',2,''c1000000-0000-0000-0000-000000000003'')') = 'err:42501', 'created_by is the caller, nobody else';
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,status,created_by) VALUES (''51000000-0000-0000-0000-0000000000a2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',2,''PUBLISHED'',''c1000000-0000-0000-0000-000000000001'')') <> 'rows:1', 'a version cannot be born PUBLISHED';
  -- edits: the draft yes; the published and the one under review no
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[{"raw":0,"scaled":9}]'' WHERE id = ''52000000-0000-0000-0000-0000000a0002''') = 'rows:1', 'the author edits a draft';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[]'' WHERE id = ''52000000-0000-0000-0000-0000000a0001''') = 'rows:0', 'not the published version';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[]'' WHERE id = ''52000000-0000-0000-0000-0000000a0003''') = 'rows:0', 'not the one under review';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''PUBLISHED'' WHERE id = ''52000000-0000-0000-0000-0000000a0002''') = 'err:23000', 'a draft cannot jump to PUBLISHED (lifecycle trigger)';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''IN_REVIEW'' WHERE id = ''52000000-0000-0000-0000-0000000a0002''') = 'rows:1', 'submit: DRAFT to IN_REVIEW';
  ASSERT public.t_attempt('UPDATE score_table_versions SET exam_code = ''TOEIC'' WHERE id = ''52000000-0000-0000-0000-0000000a0002''') = 'err:42501', 'the identity columns have no UPDATE grant';
  ASSERT public.t_attempt('UPDATE score_table_versions SET owner_org_id = NULL WHERE id = ''52000000-0000-0000-0000-0000000a0002''') = 'err:42501';
  ASSERT public.t_attempt('DELETE FROM score_table_versions WHERE id = ''52000000-0000-0000-0000-0000000a0001''') = 'rows:0', 'the published version is not deletable';
  ASSERT public.t_attempt('DELETE FROM score_table_versions WHERE id = ''52000000-0000-0000-0000-0000000a0003''') = 'rows:0', 'nor the one under review';
  -- skills
  ASSERT public.t_attempt('INSERT INTO grading_skills(owner_org_id,exam_code,question_type_code) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_W_AC_TASK1'')') = 'rows:1', 'the author creates a skill for a type of the exam';
  ASSERT public.t_attempt('INSERT INTO grading_skills(owner_org_id,exam_code,question_type_code) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''TOEIC_P1_PHOTO'')') = 'err:23503', 'a type of another exam is refused (composite key)';
  ASSERT public.t_attempt('INSERT INTO grading_skills(owner_org_id,exam_code,question_type_code) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_W_TASK2'')') = 'err:23505', 'one skill per type and owner';
  ASSERT public.t_attempt('UPDATE grading_skill_versions SET rubric = ''new'' WHERE id = ''62000000-0000-0000-0000-0000000a0002''') = 'rows:1', 'draft skill edited';
  ASSERT public.t_attempt('UPDATE grading_skill_versions SET rubric = ''new'' WHERE id = ''62000000-0000-0000-0000-0000000a0001''') = 'rows:0', 'published skill is frozen for the author';
  -- policies: a test policy for a test of org A, but not the organisation's own, nor the exam default
  ASSERT public.t_msg('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''TEST'',gen_random_uuid(),''AI'',''c1000000-0000-0000-0000-000000000001'')') LIKE '23514%grading_policies_test_scope_refused_ck%',
         'the mode of a test cannot be set until P2.6 (the author passed RLS; the CHECK refused)';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000001'')') = 'err:42501', 'but not the organisation''s mode';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (NULL,''TOEIC'',''EXAM'',''AI'',''c1000000-0000-0000-0000-000000000001'')') = 'err:42501', 'nor the exam''s';
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''AI'' WHERE id = ''63000000-0000-0000-0000-00000000000a''') = 'rows:0', 'nor change the organisation''s mode';
END $$;

-- ---- TOEIC author of org A: no IELTS table ----------------------------------------------------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE status NOT IN ('PUBLISHED','ARCHIVED')) = 0, 'a TOEIC author reads no IELTS draft';
  ASSERT (SELECT count(*) FROM grading_skill_versions) = 0, 'nor an IELTS skill';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[]'' WHERE id = ''52000000-0000-0000-0000-0000000a0002''') = 'rows:0', 'nor edit an IELTS draft';
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''READING'',''TEST'',gen_random_uuid())') LIKE '23514%score_tables_test_scope_refused_ck%',
         'but passes RLS for TOEIC tables (only the P2.6 refusal stops it)';
END $$;

-- ---- reviewer of IELTS in org A -------------------------------------------------------------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND status = 'IN_REVIEW') = 2, 'the reviewer reads what is under review (the fixture and the author''s submission)';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[]'' WHERE id = ''52000000-0000-0000-0000-0000000a0003''') = 'err:23000', 'a version under review is frozen even for the reviewer (the lifecycle trigger)';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''PUBLISHED'' WHERE id = ''52000000-0000-0000-0000-0000000a0003''') = 'rows:1', 'the reviewer publishes a version under review';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''ARCHIVED'' WHERE id = ''52000000-0000-0000-0000-0000000a0001''') = 'rows:1', 'and archives the published version it replaces';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''PUBLISHED'' WHERE id = ''52000000-0000-0000-0000-0000000b0002''') = 'rows:0', 'nothing in org B';
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,created_by) VALUES (''51000000-0000-0000-0000-00000000000a'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',7,''c1000000-0000-0000-0000-000000000002'')') = 'err:42501', 'a reviewer writes no draft';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''PUBLISHED'' WHERE score_table_id = ''51000000-0000-0000-0000-0000000000a2'' AND status = ''DRAFT''') = 'rows:0', 'a draft is not the reviewer''s to publish';
END $$;

-- ---- teacher of IELTS in org A: the published skill, not the drafts ----------------------------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000009',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_skill_versions) = 1 AND (SELECT status FROM grading_skill_versions) = 'PUBLISHED', 'the teacher reads the published skill of the organisation';
  ASSERT (SELECT count(*) FROM grading_skills) >= 1, 'and its header';
  ASSERT public.t_attempt('UPDATE grading_skill_versions SET rubric = ''x''') = 'rows:0', 'but edits nothing';
END $$;

-- ---- ORG_ADMIN of org A: the organisation's own mode ---------------------------------------------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000008',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''BOTH'', updated_by = ''c1000000-0000-0000-0000-000000000008'' WHERE id = ''63000000-0000-0000-0000-00000000000a''') = 'rows:1', 'the ORG_ADMIN changes the mode of its organisation';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000008'')') = 'rows:1', 'and adds one for another exam';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''TOEIC'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000008'')') = 'err:42501', 'never for another organisation';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (NULL,''TOEIC'',''EXAM'',''AI'',''c1000000-0000-0000-0000-000000000008'')') = 'err:42501', 'nor the exam default';
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''AI'', updated_by = ''c1000000-0000-0000-0000-000000000008'' WHERE id = ''63000000-0000-0000-0000-0000000000e9''') = 'rows:0', 'nor the exam default by update';
  ASSERT public.t_attempt('UPDATE grading_policies SET owner_org_id = ''bbbbbbbb-0000-0000-0000-00000000000b'' WHERE id = ''63000000-0000-0000-0000-00000000000a''') = 'err:42501', 'the owner of a policy cannot change';
  ASSERT public.t_attempt('DELETE FROM grading_policies WHERE id = ''63000000-0000-0000-0000-00000000000a''') = 'rows:1', 'it can drop its policy (the exam default applies again)';
  ASSERT public.t_attempt('DELETE FROM grading_policies WHERE id = ''63000000-0000-0000-0000-0000000000e9''') = 'rows:0', 'but not the exam default';
  -- org B has an IELTS ORG policy this admin cannot see: it must not block org A's own (the key carries the owner)
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000008'')') = 'rows:1',
         'org A sets its IELTS policy although org B has one (no 23505 on an invisible row)';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000008'')') = 'err:23505', 'but not twice';
END $$;

-- ---- ORG_ADMIN of an ENTERPRISE organisation: no teachers there (spec section 4), so no TEACHER / BOTH mode (P6.5 relies on this) -------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-00000000000c',false), set_config('app.org_id','cccccccc-0000-0000-0000-00000000000c',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''cccccccc-0000-0000-0000-00000000000c'',''IELTS'',''ORG'',''TEACHER'',''c1000000-0000-0000-0000-00000000000c'')') = 'err:42501', 'an ENTERPRISE admin cannot choose TEACHER';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''cccccccc-0000-0000-0000-00000000000c'',''IELTS'',''ORG'',''BOTH'',''c1000000-0000-0000-0000-00000000000c'')') = 'err:42501', 'nor BOTH';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''cccccccc-0000-0000-0000-00000000000c'',''IELTS'',''ORG'',''AI'',''c1000000-0000-0000-0000-00000000000c'')') = 'rows:1', 'but AI is fine';
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''TEACHER'', updated_by = ''c1000000-0000-0000-0000-00000000000c'' WHERE owner_org_id = ''cccccccc-0000-0000-0000-00000000000c''') = 'err:42501', 'nor can it be switched to TEACHER afterwards';
END $$;

-- ---- English9x: author, reviewer and SYS_ADMIN, in the personal context with the staff flag --------------------------------------------
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000005',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id IS NULL AND status = 'DRAFT') = 1, 'the English9x author reads English9x drafts';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id IS NOT NULL) = 0, 'and nothing of any organisation';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[{"raw":0,"scaled":3}]'' WHERE id = ''52000000-0000-0000-0000-000000e90002''') = 'rows:1', 'edits an English9x draft';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope) VALUES (NULL,''IELTS'',''READING_AC'',''EXAM'')') = 'rows:1', 'creates the exam default of IELTS Reading AC';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope) VALUES (NULL,''IELTS'',''READING_GT'',''EXAM'')') = 'rows:1', 'and, separately, of Reading GT (spec 3.3: two tables)';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope) VALUES (NULL,''IELTS'',''READING_AC'',''EXAM'')') = 'err:23505', 'one default per exam and skill';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope) VALUES (NULL,''TOEIC'',''READING'',''EXAM'')') = 'err:42501', 'not for TOEIC';
END $$;
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000006',false);
DO $$
BEGIN
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''IN_REVIEW'' WHERE id = ''52000000-0000-0000-0000-000000e90002''') = 'rows:0', 'the reviewer cannot submit (that is the author''s)';
END $$;
-- the same English9x author inside org A's context has no English9x rights
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000005',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id IS NULL AND status = 'DRAFT') = 0, 'no English9x draft is visible from an organisation context';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope) VALUES (NULL,''IELTS'',''SPEAKING'',''EXAM'')') = 'err:42501', 'and none can be written from there';
END $$;
-- SYS_ADMIN sets the exam default
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000007',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (NULL,''TOEIC'',''EXAM'',''TEACHER'',''c1000000-0000-0000-0000-000000000007'')') = 'rows:1', 'SYS_ADMIN sets the exam default';
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''BOTH'', updated_by = ''c1000000-0000-0000-0000-000000000007'' WHERE id = ''63000000-0000-0000-0000-0000000000e9''') = 'rows:1', 'and changes it';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''ORG'',''AI'',''c1000000-0000-0000-0000-000000000007'')') = 'err:42501', 'but is not the ORG_ADMIN of any organisation';
  ASSERT public.t_attempt('DELETE FROM grading_policies WHERE id = ''63000000-0000-0000-0000-0000000000e9''') = 'rows:0', 'the exam default is never deleted';
END $$;

-- ---- the grader login: published rows of English9x and of the bound organisation, nothing else -----------------------------------------
SET ROLE e9x_grader;
SELECT set_config('app.user_id','c1000000-0000-0000-0000-000000000003',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE status NOT IN ('PUBLISHED','ARCHIVED')) = 0, 'the grader reads no draft score table';
  ASSERT (SELECT count(*) FROM score_table_versions WHERE owner_org_id = 'bbbbbbbb-0000-0000-0000-00000000000b') = 0, 'none of org B';
  ASSERT (SELECT count(*) FROM score_table_versions) >= 4, 'but the published ones of English9x and of org A';
  ASSERT (SELECT count(*) FROM grading_skill_versions) = 2 AND (SELECT count(*) FROM grading_skill_versions WHERE status <> 'PUBLISHED') = 0, 'published skills of English9x and org A only';
  ASSERT (SELECT count(*) FROM grading_policies WHERE owner_org_id = 'bbbbbbbb-0000-0000-0000-00000000000b') = 0, 'no policy of org B';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,mode) VALUES (NULL,''TOEIC'',''EXAM'',''AI'')') = 'err:42501', 'the grader writes nothing';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''ARCHIVED''') = 'err:42501';
END $$;

-- ---- role_grants.exam_code is an exam of the catalog (the constant KNOWN_EXAMS is gone) -----------------------------------------------
RESET ROLE;
DO $$
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('c1000000-0000-0000-0000-000000000003',NULL,'GMAT','AUTHOR');
    ASSERT false, 'a grant for an exam that is not in the catalog was accepted';
  EXCEPTION WHEN foreign_key_violation THEN NULL; END;
END $$;
