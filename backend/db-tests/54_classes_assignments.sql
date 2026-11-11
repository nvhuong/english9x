-- P6.2: classes / groups and assignments. The role matrix: ORG_ADMIN and the class's TEACHER manage; a LEARNER writes nothing.
INSERT INTO users(id,email,email_verified_at) VALUES
 ('a0000000-0000-0000-0000-000000000102','admin-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000103','learner-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000104','learner2-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000105','teacher-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000106','admin-b@x.test', now()),
 ('a0000000-0000-0000-0000-000000000107','teacher2-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000108','ent-admin@x.test', now()),
 ('a0000000-0000-0000-0000-000000000109','ent-learner@x.test', now());
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-0000000000a1','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-0000000000b1','Center B','CENTER'),
 ('eeeeeeee-0000-0000-0000-0000000000e1','Corp E','ENTERPRISE');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000102'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000103'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000104'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000105'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000107'),
 ('bbbbbbbb-0000-0000-0000-0000000000b1','a0000000-0000-0000-0000-000000000106'),
 ('eeeeeeee-0000-0000-0000-0000000000e1','a0000000-0000-0000-0000-000000000108'),
 ('eeeeeeee-0000-0000-0000-0000000000e1','a0000000-0000-0000-0000-000000000109');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000102','aaaaaaaa-0000-0000-0000-0000000000a1',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000103','aaaaaaaa-0000-0000-0000-0000000000a1',NULL,'LEARNER'),
 ('a0000000-0000-0000-0000-000000000104','aaaaaaaa-0000-0000-0000-0000000000a1',NULL,'LEARNER'),
 ('a0000000-0000-0000-0000-000000000105','aaaaaaaa-0000-0000-0000-0000000000a1','IELTS','TEACHER'),
 ('a0000000-0000-0000-0000-000000000107','aaaaaaaa-0000-0000-0000-0000000000a1','IELTS','TEACHER'),
 ('a0000000-0000-0000-0000-000000000106','bbbbbbbb-0000-0000-0000-0000000000b1',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000108','eeeeeeee-0000-0000-0000-0000000000e1',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000109','eeeeeeee-0000-0000-0000-0000000000e1',NULL,'LEARNER');
-- a published English9x test and a class of A taught by teacher 105, with learner 103 in it
INSERT INTO tests(id,owner_org_id,exam_code,created_by) VALUES ('7e570000-0000-0000-0000-000000000001', NULL, 'IELTS', 'a0000000-0000-0000-0000-000000000102');
INSERT INTO test_versions(id,test_id,owner_org_id,exam_code,version_no,status,title,created_by)
  VALUES ('7e570000-0000-0000-0000-0000000000a1','7e570000-0000-0000-0000-000000000001',NULL,'IELTS',1,'PUBLISHED','Mock 1','a0000000-0000-0000-0000-000000000102');
INSERT INTO classes(id,owner_org_id,name,teacher_id) VALUES
 ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','Lop A','a0000000-0000-0000-0000-000000000105');
INSERT INTO class_members(class_id,org_id,user_id) VALUES
 ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000103');

-- the superuser rules: an enterprise's class is a GROUP and cannot have a teacher; a centre's teacher must hold TEACHER there
DO $$
BEGIN
  INSERT INTO classes(id,owner_org_id,name) VALUES ('c1a55000-0000-0000-0000-0000000000e1','eeeeeeee-0000-0000-0000-0000000000e1','Group E');
  ASSERT (SELECT kind FROM classes WHERE id = 'c1a55000-0000-0000-0000-0000000000e1') = 'GROUP', 'an enterprise class is a GROUP';
  BEGIN
    INSERT INTO classes(owner_org_id,name,teacher_id) VALUES ('eeeeeeee-0000-0000-0000-0000000000e1','Bad','a0000000-0000-0000-0000-000000000108');
    RAISE EXCEPTION 'an enterprise group must not take a teacher';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO classes(owner_org_id,name,teacher_id) VALUES ('aaaaaaaa-0000-0000-0000-0000000000a1','Bad','a0000000-0000-0000-0000-000000000103');
    RAISE EXCEPTION 'a learner cannot be the teacher of a class';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO classes(owner_org_id,name,teacher_id) VALUES ('aaaaaaaa-0000-0000-0000-0000000000a1','Bad','a0000000-0000-0000-0000-000000000106');
    RAISE EXCEPTION 'a teacher from another organisation is refused';
  EXCEPTION WHEN foreign_key_violation OR check_violation THEN NULL; END;
END $$;

SET ROLE e9x_app;

-- ===== LEARNER (member of A, member of the class): reads, writes nothing =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000103',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-0000000000a1',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM classes) = 1, 'the learner sees the class list of the organisation';
  BEGIN
    INSERT INTO classes(owner_org_id,name) VALUES ('aaaaaaaa-0000-0000-0000-0000000000a1','Learner class');
    RAISE EXCEPTION 'a learner must not create a class';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE classes SET teacher_id = NULL;
  ASSERT (SELECT teacher_id FROM classes) IS NOT NULL, 'a learner updates no class (policy filters the row)';
  DELETE FROM classes;
  ASSERT (SELECT count(*) FROM classes) = 1, 'a learner deletes no class (policy filters the rows)';
  BEGIN
    INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000104');
    RAISE EXCEPTION 'a learner must not insert class_members';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000103') ON CONFLICT DO NOTHING;
    RAISE EXCEPTION 'a learner must not insert class_members, not even themselves';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  DELETE FROM class_members WHERE class_id = 'c1a55000-0000-0000-0000-0000000000a1';
  ASSERT (SELECT count(*) FROM class_members WHERE class_id = 'c1a55000-0000-0000-0000-0000000000a1') = 1, 'a learner deletes no class_members (not even their own row)';
  BEGIN
    INSERT INTO assignments(class_id,org_id,test_version_id,due_at,created_by) VALUES
      ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','7e570000-0000-0000-0000-0000000000a1', now()+interval '1 day','a0000000-0000-0000-0000-000000000103');
    RAISE EXCEPTION 'a learner must not create an assignment';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO assignment_submissions(assignment_id,org_id,user_id,status,submitted_at) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000103','SUBMITTED',now());
    RAISE EXCEPTION 'nobody writes assignment_submissions directly';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT classroom_record_submission(gen_random_uuid(), 'a0000000-0000-0000-0000-000000000103', gen_random_uuid()) = 0, 'an event naming no real attempt writes nothing';
  ASSERT NOT classroom_is_recipient(gen_random_uuid(), 'a0000000-0000-0000-0000-000000000103'), 'no such assignment: nobody is a recipient';
  BEGIN
    PERFORM * FROM classroom_class_members('c1a55000-0000-0000-0000-0000000000a1');
    ASSERT NOT FOUND, 'a learner gets no roster';
  END;
END $$;

-- ===== another learner of A, not in the class: sees no class =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000104',false);
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM classes) = 1, 'the class list is the organisation''s';
  ASSERT (SELECT count(*) FROM class_members) = 0 AND (SELECT count(*) FROM assignments) = 0, 'but a member outside the class sees neither roster nor assignments';
END $$;

-- ===== the TEACHER of the class manages its roster and assignments =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000105',false);
DO $$
DECLARE a uuid;
BEGIN
  ASSERT (SELECT count(*) FROM classes) = 1, 'the teacher sees their class';
  BEGIN
    INSERT INTO classes(owner_org_id,name) VALUES ('aaaaaaaa-0000-0000-0000-0000000000a1','Teacher class');
    RAISE EXCEPTION 'a teacher must not create a class';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000104');
  ASSERT (SELECT count(*) FROM class_members) = 2, 'the teacher added a learner and sees the roster';
  ASSERT (SELECT count(*) FROM classroom_class_members('c1a55000-0000-0000-0000-0000000000a1')) = 2, 'the roster function answers';
  ASSERT (SELECT bool_and(r_email IS NULL) FROM classroom_class_members('c1a55000-0000-0000-0000-0000000000a1')), 'a teacher does not read e-mail addresses';
  -- somebody outside the organisation cannot be added
  BEGIN
    INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000106');
    RAISE EXCEPTION 'a non-member must not be added';
  EXCEPTION WHEN foreign_key_violation THEN NULL; END;
  INSERT INTO assignments(class_id,org_id,test_version_id,due_at,created_by) VALUES
    ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','7e570000-0000-0000-0000-0000000000a1', now()+interval '1 day','a0000000-0000-0000-0000-000000000105')
    RETURNING id INTO a;
  ASSERT (SELECT count(*) FROM assignments) = 1, 'the teacher gave an assignment';
  PERFORM set_config('t.assignment', a::text, false);
  -- created_by cannot be somebody else
  BEGIN
    INSERT INTO assignments(class_id,org_id,test_version_id,due_at,created_by) VALUES
      ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','7e570000-0000-0000-0000-0000000000a1', now()+interval '1 day','a0000000-0000-0000-0000-000000000102');
    RAISE EXCEPTION 'created_by must be the caller';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  -- a draft / unknown target is refused by the guard
  BEGIN
    INSERT INTO assignments(class_id,org_id,test_version_id,due_at,created_by) VALUES
      ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1',gen_random_uuid(), now()+interval '1 day','a0000000-0000-0000-0000-000000000105');
    RAISE EXCEPTION 'an unknown target must be refused';
  EXCEPTION WHEN foreign_key_violation OR check_violation THEN NULL; END;
  ASSERT (SELECT count(*) FROM classroom_assignment_report(a, now())) = 2, 'the report lists both members';
  ASSERT (SELECT r_total FROM classroom_class_assignments('c1a55000-0000-0000-0000-0000000000a1', now() + interval '2 days')) = 2 AND (SELECT r_missing FROM classroom_class_assignments('c1a55000-0000-0000-0000-0000000000a1', now() + interval '2 days')) = 2 AND (SELECT r_title FROM classroom_class_assignments('c1a55000-0000-0000-0000-0000000000a1', now())) = 'Mock 1', 'the class list counts and names';
  ASSERT (SELECT count(*) FROM classroom_assignment_report(a, now()) WHERE r_status = 'PENDING') = 2, 'nobody has handed in and it is not due';
  ASSERT (SELECT count(*) FROM classroom_assignment_report(a, now() + interval '2 days') WHERE r_status = 'MISSING') = 2, 'after the due date: MISSING';
  DELETE FROM class_members WHERE user_id = 'a0000000-0000-0000-0000-000000000104';
  ASSERT (SELECT count(*) FROM class_members) = 1, 'the teacher removed a learner';
END $$;

-- ===== the other teacher of A (not this class's) =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000107',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM assignments) = 0 AND (SELECT count(*) FROM class_members) = 0, 'a teacher of nothing sees no roster and no assignment';
  ASSERT (SELECT count(*) FROM classroom_class_assignments('c1a55000-0000-0000-0000-0000000000a1', now())) = 0 AND (SELECT count(*) FROM classroom_assignment_report(current_setting('t.assignment')::uuid, now())) = 0, 'and no report either';
  BEGIN
    INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000104');
    RAISE EXCEPTION 'another teacher must not add to this class';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- ===== the learner sees the assignment and its status, and only their own submission =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000103',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM assignments) = 1, 'the learner reads the assignment of their class';
  ASSERT (SELECT r_status FROM classroom_my_assignments(now())) = 'PENDING', 'my assignments: pending';
  ASSERT (SELECT r_status FROM classroom_my_assignments(now() + interval '2 days')) = 'MISSING', 'my assignments: missing after the due date';
  ASSERT (SELECT r_title FROM classroom_my_assignments(now())) = 'Mock 1', 'my assignments name the test';
END $$;

-- ===== the ORG_ADMIN of A: creates classes, manages; the admin of B sees none of it =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000102',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  INSERT INTO classes(id,owner_org_id,name,teacher_id) VALUES ('c1a55000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-0000000000a1','Lop A2','a0000000-0000-0000-0000-000000000107');
  ASSERT (SELECT count(*) FROM classes) = 2, 'the admin sees every class of A';
  INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000104');
  DELETE FROM class_members WHERE class_id = 'c1a55000-0000-0000-0000-0000000000a2';
  ASSERT (SELECT count(*) FROM class_members WHERE class_id = 'c1a55000-0000-0000-0000-0000000000a2') = 0, 'the admin manages the roster';
  ASSERT (SELECT bool_and(r_email IS NOT NULL) FROM classroom_class_members('c1a55000-0000-0000-0000-0000000000a1')), 'the admin reads the addresses';
  BEGIN
    INSERT INTO classes(owner_org_id,name) VALUES ('bbbbbbbb-0000-0000-0000-0000000000b1','Cross');
    RAISE EXCEPTION 'an admin must not create a class in another organisation';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000106',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-0000000000b1',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM classes WHERE owner_org_id <> 'bbbbbbbb-0000-0000-0000-0000000000b1') = 0 AND (SELECT count(*) FROM assignments) = 0 AND (SELECT count(*) FROM class_members) = 0, 'the admin of B sees nothing of A';
  BEGIN
    INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000104');
    RAISE EXCEPTION 'B admin must not write A roster';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
-- a context mismatch: A's admin presenting B's org id sees nothing and cannot write A's class
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000102',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-0000000000b1',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM classes WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-0000000000a1') = 0, 'no class of A in B''s context'; END $$;

-- ===== an enterprise's learner and admin: group, no teacher =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000108',false), set_config('app.org_id','eeeeeeee-0000-0000-0000-0000000000e1',false);
DO $$
BEGIN
  INSERT INTO classes(owner_org_id,name) VALUES ('eeeeeeee-0000-0000-0000-0000000000e1','Group E2');
  ASSERT (SELECT kind FROM classes WHERE name = 'Group E2') = 'GROUP' AND (SELECT teacher_id FROM classes WHERE name = 'Group E2') IS NULL, 'an enterprise admin makes a group with no teacher';
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000109',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO classes(owner_org_id,name) VALUES ('eeeeeeee-0000-0000-0000-0000000000e1','Learner group');
    RAISE EXCEPTION 'an enterprise learner must not create a group';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET ROLE;

-- ===== the writers and the worker's reads (superuser stands for the definer owner; e9x_worker is the role that may run them) =====
DO $$
DECLARE
  a uuid := (SELECT id FROM assignments LIMIT 1);
  att uuid := gen_random_uuid();
BEGIN
  INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason,interrupted)
    VALUES (att,'a0000000-0000-0000-0000-000000000103','aaaaaaaa-0000-0000-0000-0000000000a1','7e570000-0000-0000-0000-0000000000a1','SUBMITTED','IELTS',2,now(),'LEARNER',false);
  ASSERT classroom_record_submission(att,'a0000000-0000-0000-0000-000000000103','7e570000-0000-0000-0000-0000000000a1') = 1, 'the attempt marks the assignment';
  ASSERT classroom_record_submission(att,'a0000000-0000-0000-0000-000000000103','7e570000-0000-0000-0000-0000000000a1') = 0, 'only once';
  ASSERT classroom_record_submission(att,'a0000000-0000-0000-0000-000000000105','7e570000-0000-0000-0000-0000000000a1') = 0, 'an event naming another person marks nothing';
  ASSERT classroom_record_submission(att,'a0000000-0000-0000-0000-000000000103', gen_random_uuid()) = 0, 'a forged test version marks nothing';
  ASSERT (SELECT status FROM assignment_submissions WHERE assignment_id = a) = 'SUBMITTED', 'in time';
  ASSERT (SELECT count(*) FROM classroom_due_soon(now(), now() + interval '2 days')) = 0, 'a handed-in assignment is not "due soon"';
  ASSERT classroom_is_recipient(a, 'a0000000-0000-0000-0000-000000000103') AND NOT classroom_is_recipient(a, 'a0000000-0000-0000-0000-000000000104'), 'recipients are the class members';
  UPDATE assignments SET due_at = now() + interval '3 hours' WHERE id = a;
  DELETE FROM assignment_submissions;
  ASSERT (SELECT count(*) FROM classroom_due_soon(now(), now() + interval '1 day')) = 1, 'not handed in and due within the window: due soon';
  ASSERT (SELECT count(*) FROM classroom_due_soon(now(), now() + interval '1 hour')) = 0, 'outside the window: not yet';
END $$;
DO $$
BEGIN
  SET LOCAL ROLE e9x_worker;
  ASSERT (SELECT count(*) FROM classroom_due_soon(now(), now() + interval '1 day')) = 1, 'the worker role runs the due-soon query';
  BEGIN
    PERFORM * FROM assignments;
    RAISE EXCEPTION 'the worker has no table access';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  RESET ROLE;
END $$;
