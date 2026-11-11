-- Shared pool (P3.1 narrowed it to role and exam; the cross-organisation pool itself is P6.5's): the owner's AUTHOR / ORG_ADMIN asks (NONE <-> PENDING),
-- only an English9x REVIEWER of the exam approves; a TEACHER-style staff flag alone is not enough.
INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES ('c8000000-0000-0000-0000-000000000001','a-author@x.test'),('c8000000-0000-0000-0000-000000000002','e9-reviewer@x.test'),
 ('c8000000-0000-0000-0000-000000000003','e9-teacher-toeic@x.test'),('c8000000-0000-0000-0000-000000000004','e9-reviewer-toeic@x.test');
INSERT INTO org_memberships(org_id,user_id) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','c8000000-0000-0000-0000-000000000001');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('c8000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','IELTS','AUTHOR'),
 ('c8000000-0000-0000-0000-000000000002',NULL,'IELTS','REVIEWER'),
 ('c8000000-0000-0000-0000-000000000003',NULL,'TOEIC','TEACHER'),
 ('c8000000-0000-0000-0000-000000000004',NULL,'TOEIC','REVIEWER');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status) VALUES
 ('dddddddd-0000-0000-0000-00000000000d','aaaaaaaa-0000-0000-0000-000000000001','IELTS','ACRS01','a','PUBLISHED'),
 ('eeeeeeee-0000-0000-0000-00000000000e','aaaaaaaa-0000-0000-0000-000000000001','IELTS','ACRS03','private','PUBLISHED');

SET ROLE e9x_app;
SELECT set_config('app.user_id','c8000000-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
SELECT set_config('app.e9x_staff','',false);
UPDATE courses SET shared_status='PENDING' WHERE code='ACRS01';
DO $$
BEGIN
  ASSERT (SELECT shared_status FROM courses WHERE code='ACRS01') = 'PENDING', 'owner author can request sharing';
  BEGIN
    UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS01';
    RAISE EXCEPTION 'owner must not self-approve';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- a person with no context sees nothing pending
SELECT set_config('app.user_id','',false);
SELECT set_config('app.org_id','',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS01') = 0, 'a guest must not see a pending course';
  UPDATE courses SET shared_status='NONE' WHERE code='ACRS01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a guest must not update';
END $$;

-- English9x TEACHER of TOEIC (staff flag on): not a reviewer; and a REVIEWER of the wrong exam: neither sees nor decides
SELECT set_config('app.e9x_staff','on',false);
SELECT set_config('app.user_id','c8000000-0000-0000-0000-000000000003',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS01') = 0, 'staff flag alone sees nothing';
  UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a TOEIC teacher does not approve an IELTS course';
END $$;
SELECT set_config('app.user_id','c8000000-0000-0000-0000-000000000004',false);
DO $$
DECLARE n int;
BEGIN
  UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a TOEIC reviewer does not approve an IELTS course';
END $$;

-- the IELTS reviewer approves, and sees only what is in the pool
SELECT set_config('app.user_id','c8000000-0000-0000-0000-000000000002',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS01') = 1, 'reviewer sees the pending course';
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS03') = 0, 'reviewer must not see an unshared org course';
  UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'the reviewer approves';
  UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS03'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'the reviewer does not approve what nobody asked for';
END $$;
-- approved + published: public
SELECT set_config('app.user_id','',false);
SELECT set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS01') = 1, 'an approved published course is in the public catalog';
END $$;
