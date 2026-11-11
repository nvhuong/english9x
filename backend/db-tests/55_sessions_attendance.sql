-- P6.3: class sessions and attendance. The role matrix: ORG_ADMIN and the class's TEACHER write; members of the class read sessions and their OWN attendance;
-- a LEARNER writes neither; outsiders (other class, other organisation) see nothing.
INSERT INTO users(id,email,email_verified_at) VALUES
 ('a0000000-0000-0000-0000-000000000202','admin-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000203','learner-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000204','learner2-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000205','teacher-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000206','admin-b@x.test', now()),
 ('a0000000-0000-0000-0000-000000000207','teacher2-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000208','outsider-a@x.test', now());
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-0000000000a1','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-0000000000b1','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000202'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000204'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000205'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000207'),
 ('aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000208'),
 ('bbbbbbbb-0000-0000-0000-0000000000b1','a0000000-0000-0000-0000-000000000206');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000202','aaaaaaaa-0000-0000-0000-0000000000a1',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000203','aaaaaaaa-0000-0000-0000-0000000000a1',NULL,'LEARNER'),
 ('a0000000-0000-0000-0000-000000000204','aaaaaaaa-0000-0000-0000-0000000000a1',NULL,'LEARNER'),
 ('a0000000-0000-0000-0000-000000000205','aaaaaaaa-0000-0000-0000-0000000000a1','IELTS','TEACHER'),
 ('a0000000-0000-0000-0000-000000000207','aaaaaaaa-0000-0000-0000-0000000000a1','IELTS','TEACHER'),
 ('a0000000-0000-0000-0000-000000000208','aaaaaaaa-0000-0000-0000-0000000000a1',NULL,'LEARNER'),
 ('a0000000-0000-0000-0000-000000000206','bbbbbbbb-0000-0000-0000-0000000000b1',NULL,'ORG_ADMIN');
-- class 1 (teacher 205): learners 203 and 204; class 2 (teacher 207): learner 208
INSERT INTO classes(id,owner_org_id,name,teacher_id) VALUES
 ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','Lop 1','a0000000-0000-0000-0000-000000000205'),
 ('c1a55000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-0000000000a1','Lop 2','a0000000-0000-0000-0000-000000000207');
INSERT INTO class_members(class_id,org_id,user_id) VALUES
 ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203'),
 ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000204'),
 ('c1a55000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000208');

-- the table rules (superuser): an ONLINE session needs an https link, an OFFLINE one has none, the end is after the start
DO $$
BEGIN
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,created_by) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','ONLINE', now(), now()+interval '1 hour','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'online without a link';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,meeting_url,created_by) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','ONLINE', now(), now()+interval '1 hour','http://meet.example/x','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'online with a plain http link';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,meeting_url,created_by) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','ONLINE', now(), now()+interval '1 hour','javascript:alert(1)','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'online with a javascript link';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,meeting_url,created_by) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now(), now()+interval '1 hour','https://meet.example/x','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'offline with a link';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,created_by) VALUES ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now(), now(),'a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'ends must follow starts';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,created_by) VALUES ('c1a55000-0000-0000-0000-0000000000a1','bbbbbbbb-0000-0000-0000-0000000000b1','OFFLINE', now(), now()+interval '1 hour','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'a class of another organisation';
  EXCEPTION WHEN foreign_key_violation THEN NULL; END;
END $$;

SET ROLE e9x_app;

-- ===== LEARNER of class 1: reads, writes nothing =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000203',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-0000000000a1',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,room,created_by) VALUES
      ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now(), now()+interval '1 hour','R1','a0000000-0000-0000-0000-000000000203');
    RAISE EXCEPTION 'a learner must not create a session';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE class_sessions SET room = 'X';
    RAISE EXCEPTION 'nobody updates a session in place';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    DELETE FROM class_sessions;
    RAISE EXCEPTION 'nobody deletes a session in place';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203','PRESENT','a0000000-0000-0000-0000-000000000203');
    RAISE EXCEPTION 'a learner must not write attendance';
  EXCEPTION WHEN insufficient_privilege OR foreign_key_violation THEN NULL; END;
END $$;

-- ===== the TEACHER of class 1 writes sessions and attendance for their class only =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000205',false);
DO $$
DECLARE s1 uuid; s2 uuid;
BEGIN
  INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,room,created_by) VALUES
    ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now() + interval '2 days', now() + interval '2 days 2 hours','R1','a0000000-0000-0000-0000-000000000205') RETURNING id INTO s1;
  INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,meeting_url,created_by) VALUES
    ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','ONLINE', now() + interval '3 days', now() + interval '3 days 1 hour','https://meet.example.org/abc','a0000000-0000-0000-0000-000000000205') RETURNING id INTO s2;
  ASSERT (SELECT count(*) FROM class_sessions) = 2, 'the teacher made two sessions';
  PERFORM set_config('t.s1', s1::text, false);
  PERFORM set_config('t.s2', s2::text, false);
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,room,created_by) VALUES
      ('c1a55000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now(), now()+interval '1 hour','R9','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'a teacher must not schedule another teacher''s class';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,room,created_by) VALUES
      ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now(), now()+interval '1 hour','R9','a0000000-0000-0000-0000-000000000202');
    RAISE EXCEPTION 'created_by must be the caller';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  INSERT INTO attendance(session_id,org_id,user_id,status,note,marked_by) VALUES
    (s1,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203','PRESENT',NULL,'a0000000-0000-0000-0000-000000000205'),
    (s1,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000204','ABSENT','sick','a0000000-0000-0000-0000-000000000205');
  ASSERT (SELECT count(*) FROM attendance) = 2, 'the teacher marked two learners';
  -- an upsert changes the status
  INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (s1,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000204','LATE','a0000000-0000-0000-0000-000000000205')
    ON CONFLICT (session_id, user_id) DO UPDATE SET status = EXCLUDED.status, note = EXCLUDED.note, marked_by = EXCLUDED.marked_by;
  ASSERT (SELECT status FROM attendance WHERE user_id = 'a0000000-0000-0000-0000-000000000204') = 'LATE', 'the mark was corrected';
  -- somebody who is not in the class cannot be marked; a bad status is refused; marked_by is the caller
  BEGIN
    INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (s1,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000208','PRESENT','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'a non-member must not be marked';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (s2,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203','MAYBE','a0000000-0000-0000-0000-000000000205');
    RAISE EXCEPTION 'unknown status';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (s2,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203','PRESENT','a0000000-0000-0000-0000-000000000202');
    RAISE EXCEPTION 'marked_by must be the caller';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM classroom_session_attendance(s1)) = 2 AND (SELECT bool_and(r_email IS NULL) FROM classroom_session_attendance(s1)), 'the roster of the session, no addresses for a teacher';
  ASSERT (SELECT count(*) FROM classroom_my_schedule(now(), now() + interval '10 days')) = 2 AND (SELECT bool_and(r_role = 'TEACHER') FROM classroom_my_schedule(now(), now() + interval '10 days')), 'the teacher''s schedule';
  ASSERT (SELECT count(*) FROM classroom_my_schedule(now() + interval '2 days 12 hours', now() + interval '10 days')) = 1, 'the schedule honours the range';
END $$;

-- ===== the other teacher (class 2): sees and writes nothing of class 1 =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000207',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM class_sessions) = 0 AND (SELECT count(*) FROM attendance) = 0, 'another teacher sees no session and no attendance of class 1';
  ASSERT (SELECT count(*) FROM classroom_session_attendance(current_setting('t.s1')::uuid)) = 0 AND (SELECT count(*) FROM classroom_attendance_report('c1a55000-0000-0000-0000-0000000000a1')) = 0, 'and no report';
  ASSERT (SELECT count(*) FROM classroom_my_schedule(now(), now() + interval '10 days')) = 0, 'and nothing in the schedule';
  BEGIN
    INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (current_setting('t.s1')::uuid,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203','ABSENT','a0000000-0000-0000-0000-000000000207');
    RAISE EXCEPTION 'another teacher must not mark class 1';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE attendance SET status = 'ABSENT';
END $$;

-- ===== learner 203 of class 1: sees the sessions, own attendance only, writes nothing =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000203',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM class_sessions) = 2, 'a member reads the sessions of their class';
  ASSERT (SELECT count(*) FROM attendance) = 1 AND (SELECT user_id FROM attendance) = 'a0000000-0000-0000-0000-000000000203', 'a member reads only their own attendance row';
  ASSERT (SELECT count(*) FROM classroom_session_attendance(current_setting('t.s1')::uuid)) = 0, 'a learner gets no session roster';
  ASSERT (SELECT count(*) FROM classroom_attendance_report('c1a55000-0000-0000-0000-0000000000a1')) = 0, 'a learner gets no report';
  ASSERT (SELECT count(*) FROM classroom_my_schedule(now(), now() + interval '10 days')) = 2 AND (SELECT bool_and(r_role = 'LEARNER') FROM classroom_my_schedule(now(), now() + interval '10 days')), 'the learner''s schedule';
  ASSERT (SELECT r_attendance FROM classroom_my_schedule(now(), now() + interval '10 days') ORDER BY r_starts_at LIMIT 1) = 'PRESENT', 'with their own mark';
  UPDATE attendance SET status = 'ABSENT';
  ASSERT (SELECT status FROM attendance) = 'PRESENT', 'a learner changes no attendance (policy filters the row)';
  BEGIN
    INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (current_setting('t.s2')::uuid,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000203','PRESENT','a0000000-0000-0000-0000-000000000203');
    RAISE EXCEPTION 'a learner must not mark themselves present';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (current_setting('t.s2')::uuid,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000204','PRESENT','a0000000-0000-0000-0000-000000000203');
    RAISE EXCEPTION 'a learner must not mark anybody';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- ===== a member of class 2, an outsider to class 1; and nobody of another organisation =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000208',false);
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM class_sessions) = 0 AND (SELECT count(*) FROM attendance) = 0, 'a member of another class sees no session and no attendance';
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000206',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-0000000000b1',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM class_sessions) = 0 AND (SELECT count(*) FROM attendance) = 0, 'the admin of B sees nothing of A';
  BEGIN
    INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,room,created_by) VALUES
      ('c1a55000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now(), now()+interval '1 hour','R9','a0000000-0000-0000-0000-000000000206');
    RAISE EXCEPTION 'the admin of B must not schedule a class of A';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- ===== the ORG_ADMIN of A: sees and writes all; reads addresses =====
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000202',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-0000000000a1',false);
DO $$
DECLARE s3 uuid;
BEGIN
  ASSERT (SELECT count(*) FROM class_sessions) = 2 AND (SELECT count(*) FROM attendance) = 2, 'the admin sees every session and mark of A';
  INSERT INTO class_sessions(class_id,org_id,mode,starts_at,ends_at,room,created_by) VALUES
    ('c1a55000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-0000000000a1','OFFLINE', now(), now()+interval '1 hour','R2','a0000000-0000-0000-0000-000000000202') RETURNING id INTO s3;
  INSERT INTO attendance(session_id,org_id,user_id,status,marked_by) VALUES (s3,'aaaaaaaa-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000208','EXCUSED','a0000000-0000-0000-0000-000000000202');
  ASSERT (SELECT bool_and(r_email IS NOT NULL) FROM classroom_session_attendance(s3)), 'the admin reads the addresses';
  -- the report: 203 PRESENT; 204 LATE (corrected) ; both over the one marked session
  ASSERT (SELECT r_rate FROM classroom_attendance_report('c1a55000-0000-0000-0000-0000000000a1') WHERE r_user_id = 'a0000000-0000-0000-0000-000000000203') = 1, '203: 1 of 1';
  -- an EXCUSED session leaves the denominator, so the only mark of 208 gives no rate
  ASSERT (SELECT r_rate FROM classroom_attendance_report('c1a55000-0000-0000-0000-0000000000a2')) IS NULL AND (SELECT r_excused FROM classroom_attendance_report('c1a55000-0000-0000-0000-0000000000a2')) = 1, '208: excused only, no rate';
END $$;
RESET ROLE;

-- ===== the worker's read of the reminder (superuser stands for the definer owner; e9x_worker may run it) =====
DO $$
BEGIN
  -- session 1 of class 1 starts in 2 days: its two learners and its teacher, once each
  ASSERT (SELECT count(*) FROM classroom_sessions_to_remind(now() + interval '47 hours', now() + interval '49 hours')) = 3, 'learners and the teacher of the class';
  ASSERT (SELECT count(DISTINCT r_user_id) FROM classroom_sessions_to_remind(now() + interval '47 hours', now() + interval '49 hours')) = 3, 'each once';
  ASSERT (SELECT count(*) FROM classroom_sessions_to_remind(now() + interval '49 hours', now() + interval '50 hours')) = 0, 'outside the window: nobody';
  SET LOCAL ROLE e9x_worker;
  ASSERT (SELECT count(*) FROM classroom_sessions_to_remind(now() + interval '47 hours', now() + interval '49 hours')) = 3, 'the worker role runs the reminder query';
  BEGIN
    PERFORM * FROM class_sessions;
    RAISE EXCEPTION 'the worker has no table access';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    PERFORM * FROM classroom_session_attendance(gen_random_uuid());
    RAISE EXCEPTION 'the worker must not run the attendance read';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  RESET ROLE;
  -- nobody else (PUBLIC) runs the worker's function: e9x_app does not either
  ASSERT NOT has_function_privilege('e9x_app', 'classroom_sessions_to_remind(timestamptz, timestamptz)', 'EXECUTE'), 'the app role does not run the reminder read';
  ASSERT NOT has_function_privilege('public', 'classroom_my_schedule(timestamptz, timestamptz)', 'EXECUTE'), 'PUBLIC runs nothing';
END $$;
