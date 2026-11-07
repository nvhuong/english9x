-- P1.6 review workflow tables: the tenant boundary and the decision rules hold in the database itself, whatever the service does.
--  * A review of org B / of English9x is invisible and untouchable to a reviewer sitting in org A (RLS).
--  * A REVIEWER of one exam cannot decide a review of another exam.
--  * The English9x queue needs the English9x flag (personal context) AND a grant for the exam; no flag, no rows.
--  * A review is decided once (IN_REVIEW -> PUBLISHED | CHANGES_REQUESTED); nothing else about it changes; the decider is
--    never its author nor the person who submitted it.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('c0000000-0000-0000-0000-000000000001','rev-a-ielts@x.test'),
 ('c0000000-0000-0000-0000-000000000002','rev-a-toeic@x.test'),
 ('c0000000-0000-0000-0000-000000000003','author-a@x.test'),
 ('c0000000-0000-0000-0000-000000000004','rev-b@x.test'),
 ('c0000000-0000-0000-0000-000000000005','author-b@x.test'),
 ('c0000000-0000-0000-0000-000000000006','rev-e9-ielts@x.test'),
 ('c0000000-0000-0000-0000-000000000007','author-e9@x.test'),
 ('c0000000-0000-0000-0000-000000000008','learner-a@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000008'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','c0000000-0000-0000-0000-000000000004'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','c0000000-0000-0000-0000-000000000005');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('c0000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('c0000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','REVIEWER'),
 ('c0000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('c0000000-0000-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('c0000000-0000-0000-0000-000000000004','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','REVIEWER'),
 ('c0000000-0000-0000-0000-000000000005','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AUTHOR'),
 ('c0000000-0000-0000-0000-000000000006',NULL,'IELTS','REVIEWER'),
 ('c0000000-0000-0000-0000-000000000007',NULL,'IELTS','AUTHOR');
INSERT INTO review_requests(id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('d0000000-0000-0000-0000-00000000000a','item','e0000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','c0000000-0000-0000-0000-000000000003','c0000000-0000-0000-0000-000000000003'),
 ('d0000000-0000-0000-0000-00000000000b','item','e0000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','c0000000-0000-0000-0000-000000000005','c0000000-0000-0000-0000-000000000005'),
 ('d0000000-0000-0000-0000-00000000000e','item','e0000000-0000-0000-0000-00000000000e',NULL,'IELTS','c0000000-0000-0000-0000-000000000007','c0000000-0000-0000-0000-000000000007');

-- ---- reviewer of org A, in org A -----------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM review_requests) = 1, 'org A reviewer sees only org A reviews';
  ASSERT (SELECT count(*) FROM review_requests WHERE owner_org_id IS NULL) = 0, 'and none of English9x';
  UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000001', decided_at=now()
   WHERE id IN ('d0000000-0000-0000-0000-00000000000b','d0000000-0000-0000-0000-00000000000e'); GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'cannot decide a review of org B or of English9x';
  BEGIN
    INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by)
    VALUES ('item',gen_random_uuid(),'bbbbbbbb-0000-0000-0000-00000000000b','IELTS','c0000000-0000-0000-0000-000000000005','c0000000-0000-0000-0000-000000000001');
    ASSERT false, 'a reviewer inserted a review into org B';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by)
    VALUES ('item',gen_random_uuid(),'aaaaaaaa-0000-0000-0000-00000000000a','IELTS','c0000000-0000-0000-0000-000000000003','c0000000-0000-0000-0000-000000000001');
    ASSERT false, 'a reviewer (not an author) submitted a review';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    DELETE FROM review_requests;
    ASSERT false, 'delete is not granted';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
-- the decision of a review of another exam
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000002',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM review_requests) = 0, 'a TOEIC reviewer does not even see the IELTS review';
  UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000002', decided_at=now()
   WHERE id = 'd0000000-0000-0000-0000-00000000000a'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a TOEIC reviewer cannot decide an IELTS review';
END $$;
-- a learner of org A
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000008',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM review_requests) = 0, 'a learner sees no review';
END $$;
-- the author sees (only) their own review, and cannot decide it
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000003',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM review_requests) = 1, 'the author sees their review';
  UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000003', decided_at=now() WHERE id = 'd0000000-0000-0000-0000-00000000000a'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'an author (no REVIEWER grant) cannot decide';
END $$;

-- ---- reviewer of English9x --------------------------------------------------------------------------------------------------
-- personal context, flag on: only the English9x reviews; org reviews stay invisible
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000006',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM review_requests) = 1 AND (SELECT count(*) FROM review_requests WHERE owner_org_id IS NULL) = 1,
         'the English9x reviewer sees the English9x review and no organisation review';
END $$;
-- same person without the flag (e.g. a forged or missing flag): nothing
SELECT set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM review_requests) = 0, 'no English9x flag, no English9x review';
END $$;
-- an English9x reviewer inside an organisation context: that organisation's reviews need an organisation grant
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM review_requests) = 0, 'an English9x grant does not read an organisation''s reviews';
END $$;

-- ---- decisions: rules enforced by the trigger -----------------------------------------------------------------------------
RESET ROLE;
-- an org A person who is both reviewer and author of a version (the grant combination the service must still refuse)
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('c0000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER');
SET ROLE e9x_app;
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000003',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  BEGIN
    UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000003', decided_at=now() WHERE id = 'd0000000-0000-0000-0000-00000000000a';
    ASSERT false, 'the author decided their own review';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
  ASSERT (SELECT status FROM review_requests WHERE id = 'd0000000-0000-0000-0000-00000000000a') = 'IN_REVIEW', 'still open';
END $$;
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int;
BEGIN
  -- the decider recorded must be the caller
  UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000002', decided_at=now() WHERE id = 'd0000000-0000-0000-0000-00000000000a'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0 OR false, 'recording someone else as the decider is not accepted';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
DO $$
DECLARE n int;
BEGIN
  -- changes requested need a comment
  BEGIN
    UPDATE review_requests SET status='CHANGES_REQUESTED', reviewer_id='c0000000-0000-0000-0000-000000000001', decided_at=now(), comment='  ' WHERE id = 'd0000000-0000-0000-0000-00000000000a';
    ASSERT false, 'changes requested without a comment';
  EXCEPTION WHEN check_violation THEN NULL; END;
  -- nothing but the decision may change
  BEGIN
    UPDATE review_requests SET exam_code='TOEIC' WHERE id = 'd0000000-0000-0000-0000-00000000000a';
    ASSERT false, 'exam_code is not updatable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE review_requests SET author_id='c0000000-0000-0000-0000-000000000001' WHERE id = 'd0000000-0000-0000-0000-00000000000a';
    ASSERT false, 'author_id is not updatable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  -- a proper decision
  UPDATE review_requests SET status='CHANGES_REQUESTED', reviewer_id='c0000000-0000-0000-0000-000000000001', decided_at=now(), comment='fix the key'
   WHERE id = 'd0000000-0000-0000-0000-00000000000a';
  ASSERT (SELECT comment FROM review_requests WHERE id = 'd0000000-0000-0000-0000-00000000000a') = 'fix the key';
  -- decided once
  BEGIN
    UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000001', decided_at=now() WHERE id = 'd0000000-0000-0000-0000-00000000000a';
    ASSERT false, 'a decided review was decided again';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
END $$;
RESET ROLE;
-- the trigger is the second line: even a writer that skips RLS (the owner, a superuser) cannot re-decide or rewrite a review
DO $$
BEGIN
  BEGIN
    UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000001', decided_at=now() WHERE id = 'd0000000-0000-0000-0000-00000000000a';
    ASSERT false, 'a decided review was decided again';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
  BEGIN
    UPDATE review_requests SET exam_code='TOEIC' WHERE id = 'd0000000-0000-0000-0000-00000000000e';
    ASSERT false, 'the exam of an open review changed';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
  BEGIN
    UPDATE review_requests SET status='PUBLISHED', reviewer_id='c0000000-0000-0000-0000-000000000007', decided_at=now() WHERE id = 'd0000000-0000-0000-0000-00000000000e';
    ASSERT false, 'the author decided on their own English9x version';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
END $$;

-- ---- one open review and one publication per version; bundle members match their bundle ----------------------------------
DO $$
BEGIN
  INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by)
  VALUES ('item','e0000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','c0000000-0000-0000-0000-000000000005','c0000000-0000-0000-0000-000000000005');
  ASSERT false, 'two open reviews for one version';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
INSERT INTO review_bundles(id,owner_org_id,submitted_by,member_count) VALUES ('f0000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000003',1);
DO $$
BEGIN
  INSERT INTO review_requests(bundle_id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by)
  VALUES ('f0000000-0000-0000-0000-00000000000a','item',gen_random_uuid(),'bbbbbbbb-0000-0000-0000-00000000000b','IELTS','c0000000-0000-0000-0000-000000000005','c0000000-0000-0000-0000-000000000005');
  ASSERT false, 'a member of another organisation joined the bundle';
EXCEPTION WHEN integrity_constraint_violation THEN NULL;
END $$;
-- privileges
DO $$
BEGIN
  ASSERT NOT has_table_privilege('e9x_app','review_requests','DELETE') AND NOT has_table_privilege('e9x_app','review_bundles','UPDATE')
     AND NOT has_table_privilege('e9x_app','review_bundles','DELETE'), 'no delete, bundles are never updated';
  ASSERT NOT has_table_privilege('e9x_grader','review_requests','SELECT') AND NOT has_table_privilege('e9x_worker','review_requests','SELECT'),
         'the grader and the worker read no reviews';
END $$;
