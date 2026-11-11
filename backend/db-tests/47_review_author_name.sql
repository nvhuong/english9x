-- P2.9 e9x_review_author_name(): the narrow SECURITY DEFINER that shows a reviewer the display name of the author of a review they may act on.
--  * Returns ONLY text (the display name). Answers for a review or a bundle id; the oldest member the caller may act on.
--  * NULL for: a reviewer of another exam, a plain AUTHOR, a review of another organisation, English9x reviews without the staff flag, nobody signed in.
--  * It does not widen the users table: the reviewer still cannot SELECT the author's row.
--  * The request role may call it, PUBLIC may not; the search_path is pinned.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email,display_name) VALUES
 ('c4700000-0000-0000-0000-000000000001','rv-ielts@x.test','Reviewer IELTS'),
 ('c4700000-0000-0000-0000-000000000002','rv-toeic@x.test','Reviewer TOEIC'),
 ('c4700000-0000-0000-0000-000000000003','au-a@x.test','Author Of A'),
 ('c4700000-0000-0000-0000-000000000004','au-b@x.test','Author Of B'),
 ('c4700000-0000-0000-0000-000000000005','au-e9@x.test','Author Of English9x'),
 ('c4700000-0000-0000-0000-000000000006','rv-e9@x.test','Reviewer English9x');
INSERT INTO organizations(id,name,type) VALUES
 ('a4700000-0000-0000-0000-00000000000a','Name Center A','CENTER'),
 ('b4700000-0000-0000-0000-00000000000b','Name Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a4700000-0000-0000-0000-00000000000a','c4700000-0000-0000-0000-000000000001'),
 ('a4700000-0000-0000-0000-00000000000a','c4700000-0000-0000-0000-000000000002'),
 ('a4700000-0000-0000-0000-00000000000a','c4700000-0000-0000-0000-000000000003'),
 ('b4700000-0000-0000-0000-00000000000b','c4700000-0000-0000-0000-000000000004');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('c4700000-0000-0000-0000-000000000001','a4700000-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('c4700000-0000-0000-0000-000000000002','a4700000-0000-0000-0000-00000000000a','TOEIC','REVIEWER'),
 ('c4700000-0000-0000-0000-000000000003','a4700000-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('c4700000-0000-0000-0000-000000000004','b4700000-0000-0000-0000-00000000000b','IELTS','AUTHOR'),
 ('c4700000-0000-0000-0000-000000000005',NULL,'IELTS','AUTHOR'),
 ('c4700000-0000-0000-0000-000000000006',NULL,'IELTS','REVIEWER');
INSERT INTO review_bundles(id,owner_org_id,submitted_by,member_count) VALUES
 ('f4700000-0000-0000-0000-0000000000b1','a4700000-0000-0000-0000-00000000000a','c4700000-0000-0000-0000-000000000003',1);
INSERT INTO review_requests(id,bundle_id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('d4700000-0000-0000-0000-00000000000a','f4700000-0000-0000-0000-0000000000b1','item',gen_random_uuid(),'a4700000-0000-0000-0000-00000000000a','IELTS','c4700000-0000-0000-0000-000000000003','c4700000-0000-0000-0000-000000000003'),
 ('d4700000-0000-0000-0000-00000000000b',NULL,'item',gen_random_uuid(),'b4700000-0000-0000-0000-00000000000b','IELTS','c4700000-0000-0000-0000-000000000004','c4700000-0000-0000-0000-000000000004'),
 ('d4700000-0000-0000-0000-00000000000e',NULL,'item',gen_random_uuid(),NULL,'IELTS','c4700000-0000-0000-0000-000000000005','c4700000-0000-0000-0000-000000000005');

DO $$
DECLARE f pg_proc%ROWTYPE;
BEGIN
  SELECT * INTO f FROM pg_proc WHERE proname = 'e9x_review_author_name';
  ASSERT f.prosecdef, 'it is SECURITY DEFINER';
  ASSERT f.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp'], 'its search_path is pinned';
  ASSERT has_function_privilege('e9x_app', f.oid, 'EXECUTE'), 'the request role may call it';
  ASSERT NOT has_function_privilege('public', f.oid, 'EXECUTE'), 'nobody else may';
  ASSERT f.prorettype = 'text'::regtype, 'it returns a bare text, no row';
END $$;

-- ---- the REVIEWER of IELTS in org A -----------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','c4700000-0000-0000-0000-000000000001',false), set_config('app.org_id','a4700000-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000a')) = 'Author Of A', 'the author of a review the reviewer may act on';
  ASSERT (SELECT e9x_review_author_name('f4700000-0000-0000-0000-0000000000b1')) = 'Author Of A', 'by the bundle id too';
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000b')) IS NULL, 'not the author of another organisation''s review';
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000e')) IS NULL, 'not the author of an English9x review (no flag, another context)';
  ASSERT (SELECT e9x_review_author_name(gen_random_uuid())) IS NULL, 'no such review';
END $$;

-- ---- the REVIEWER of TOEIC (another exam) and the AUTHOR in the same organisation ------------------------------------------
SELECT set_config('app.user_id','c4700000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000a')) IS NULL, 'a reviewer of another exam gets nothing';
  ASSERT (SELECT e9x_review_author_name('f4700000-0000-0000-0000-0000000000b1')) IS NULL, 'nor through the bundle';
END $$;
SELECT set_config('app.user_id','c4700000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000a')) IS NULL, 'an author (no REVIEWER grant) gets nothing, not even their own name';
END $$;

-- ---- the same reviewer bound to the wrong organisation, and nobody signed in -------------------------------------------------
SELECT set_config('app.user_id','c4700000-0000-0000-0000-000000000001',false), set_config('app.org_id','b4700000-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000b')) IS NULL, 'a reviewer of A has no grant in B: org B''s review stays anonymous';
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000a')) IS NULL, 'and A''s review is not in the context of B';
END $$;
SELECT set_config('app.user_id','',false), set_config('app.org_id','a4700000-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000a')) IS NULL, 'nobody signed in, no name';
END $$;

-- ---- English9x: the staff flag and the grant, in the personal context -------------------------------------------------------
SELECT set_config('app.user_id','c4700000-0000-0000-0000-000000000006',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000e')) = 'Author Of English9x', 'English9x reviewer sees the English9x author';
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000a')) IS NULL, 'but not an organisation''s author';
  ASSERT (SELECT count(*) FROM users WHERE id = 'c4700000-0000-0000-0000-000000000005') = 0, 'and still cannot read that colleague''s users row';
END $$;
SELECT set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT e9x_review_author_name('d4700000-0000-0000-0000-00000000000e')) IS NULL, 'without the staff flag the English9x name is not shown';
END $$;
RESET ROLE;
