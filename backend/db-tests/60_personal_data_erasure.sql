-- P6.5 branch D — personal-data erasure (V202611170004, spec §15, Nghị định 13/2023). erase_personal_data scrubs one person's personal data in a single audited,
-- narrow SECURITY DEFINER; audit_log (ids only, append-only) is never touched.
INSERT INTO users(id,email,display_name,email_verified_at) VALUES
 ('60000000-0000-0000-0000-000000000001','erase.me@x.test','Erase Me', now()),
 ('60000000-0000-0000-0000-000000000002','inviter@x.test','Inviter', now());
INSERT INTO auth_identities(user_id,provider,subject,email) VALUES ('60000000-0000-0000-0000-000000000001','google','g-erase-subject','erase.me@x.test');
INSERT INTO organizations(id,name,type) VALUES ('60aaaaaa-0000-0000-0000-00000000000a','Erase Org','CENTER');
INSERT INTO invitations(id,org_id,email,grants,token_hash,invited_by,expires_at,revoked_at) VALUES
 ('60111111-0000-0000-0000-000000000011','60aaaaaa-0000-0000-0000-00000000000a','erase.me@x.test','[{"role":"LEARNER"}]','\x01','60000000-0000-0000-0000-000000000002', now()+interval '7 days', NULL),
 ('60111111-0000-0000-0000-000000000012','60aaaaaa-0000-0000-0000-00000000000a','erase.me@x.test','[{"role":"LEARNER"}]','\x02','60000000-0000-0000-0000-000000000002', now()-interval '1 day', now()); -- a revoked, expired one too
INSERT INTO notifications(id,user_id,event_type,data,dedupe_key,created_at) VALUES ('60222222-0000-0000-0000-000000000021','60000000-0000-0000-0000-000000000001','GRADING_QUEUE_NEW','{}','erase-test-1', now());
INSERT INTO notification_deliveries(notification_id,channel,status) VALUES ('60222222-0000-0000-0000-000000000021','EMAIL','SENT');
INSERT INTO notification_prefs(user_id,event_type,channel,enabled) VALUES ('60000000-0000-0000-0000-000000000001','GRADING_QUEUE_NEW','EMAIL', true);
INSERT INTO jobs(id,type,payload,status,run_at) VALUES
 ('60333333-0000-0000-0000-000000000031','notify_send','{"userId":"60000000-0000-0000-0000-000000000001"}','SUCCEEDED', now()),
 ('60333333-0000-0000-0000-000000000032','notify_send','{"userId":"60000000-0000-0000-0000-000000000001"}','QUEUED', now()); -- a live job is left alone
INSERT INTO event_publication(id,listener_id,event_type,serialized_event,publication_date,completion_date) VALUES
 (gen_random_uuid(),'L','E','{"userId":"60000000-0000-0000-0000-000000000001"}', now(), now()),             -- completed: scrubbed
 (gen_random_uuid(),'L','E','{"userId":"60000000-0000-0000-0000-000000000001"}', now(), NULL);                -- incomplete: left (pending redelivery)

-- the person's attempt data: an essay (responses.answer), a Speaking recording (speaking_recordings), a teacher's feedback (teacher_reviews.comment) and a paid order (orders.bank_ref)
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email,email_verified_at) VALUES ('60000000-0000-0000-0000-000000000003','erase-teacher@x.test', now());
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,interrupted,submit_reason) VALUES
 ('60444444-0000-0000-0000-000000000041','60000000-0000-0000-0000-000000000001','60aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'SUBMITTED','IELTS',2, now(), false,'LEARNER');
INSERT INTO attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) VALUES
 ('60444444-0000-0000-0000-000000000041','60444444-0000-0000-0000-0000000000a1','60000000-0000-0000-0000-000000000001',gen_random_uuid(),'WRITING'),
 ('60444444-0000-0000-0000-000000000041','60444444-0000-0000-0000-0000000000a2','60000000-0000-0000-0000-000000000001',gen_random_uuid(),'SPEAKING');
INSERT INTO responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) VALUES
 ('60444444-0000-0000-0000-000000000041','60444444-0000-0000-0000-0000000000a1','60000000-0000-0000-0000-000000000001','"My Writing essay: I grew up in Hanoi and my family runs a small shop."'::jsonb, now(), now());
INSERT INTO speaking_recordings(attempt_id,question_id,user_id,storage_path,mime_type,size_bytes,uploaded_at) VALUES
 ('60444444-0000-0000-0000-000000000041','60444444-0000-0000-0000-0000000000a2','60000000-0000-0000-0000-000000000001','staging/recordings/60444444-0000-0000-0000-000000000041/60444444-0000-0000-0000-0000000000a2.webm','audio/webm',1234, now());
INSERT INTO grading_queue_items(id,attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES
 ('60444444-0000-0000-0000-000000000051','60444444-0000-0000-0000-000000000041','60000000-0000-0000-0000-000000000001','60aaaaaa-0000-0000-0000-00000000000a','60aaaaaa-0000-0000-0000-00000000000a','IELTS','POLICY');
INSERT INTO teacher_reviews(queue_item_id,attempt_id,user_id,owner_org_id,skill,teacher_id,criteria,comment,band) VALUES
 ('60444444-0000-0000-0000-000000000051','60444444-0000-0000-0000-000000000041','60000000-0000-0000-0000-000000000001','60aaaaaa-0000-0000-0000-00000000000a','WRITING','60000000-0000-0000-0000-000000000003','{"TA":6}'::jsonb,'Good structure, Nguyen. Work on cohesion.',6);
INSERT INTO plans(id,name,price_vnd,months,max_courses) VALUES ('60555555-0000-0000-0000-000000000061','Erase plan',100000,1,2);
INSERT INTO orders(id,code,user_id,plan_id,plan_months,amount_vnd,status,bank_ref,confirmed_at) VALUES
 ('60555555-0000-0000-0000-000000000062','ER0001','60000000-0000-0000-0000-000000000001','60555555-0000-0000-0000-000000000061',1,100000,'CONFIRMED','Nguyen Van A chuyen khoan ER0001', now());

DO $$
BEGIN
  ASSERT has_function_privilege('e9x_app','erase_personal_data(uuid)','EXECUTE'), 'the request role may run the erasure';
  ASSERT NOT has_function_privilege('public','erase_personal_data(uuid)','EXECUTE'), 'PUBLIC was revoked';
  ASSERT (SELECT prosecdef AND proconfig::text LIKE '%search_path=pg_catalog, public, pg_temp%' FROM pg_proc WHERE proname = 'erase_personal_data'),
     'the erasure is a definer with a pinned search_path';
  ASSERT NOT has_table_privilege('e9x_app','audit_log','DELETE') AND NOT has_table_privilege('e9x_app','audit_log','UPDATE'),
     'erasure gives the request role no new right over the append-only log';
  ASSERT has_function_privilege('e9x_app','personal_data_recording_keys(uuid)','EXECUTE')
     AND NOT has_function_privilege('public','personal_data_recording_keys(uuid)','EXECUTE')
     AND (SELECT prosecdef AND proconfig::text LIKE '%search_path=pg_catalog, public, pg_temp%' FROM pg_proc WHERE proname = 'personal_data_recording_keys'),
     'the recording-keys lister is a narrow definer the request role may run';
END $$;

SELECT erase_personal_data('60000000-0000-0000-0000-000000000001');

DO $$
BEGIN
  -- the original address appears nowhere any more
  ASSERT (SELECT count(*) FROM users WHERE email = 'erase.me@x.test') = 0, 'the email is gone from users';
  ASSERT (SELECT email <> 'erase.me@x.test' AND display_name IS NULL AND password_hash IS NULL FROM users WHERE id = '60000000-0000-0000-0000-000000000001'),
     'the person''s own fields are scrubbed, the account id survives';
  ASSERT (SELECT count(*) FROM auth_identities WHERE user_id = '60000000-0000-0000-0000-000000000001') = 0, 'the sign-in identities are gone';
  ASSERT (SELECT count(*) FROM invitations WHERE email = 'erase.me@x.test') = 0, 'the address is gone from every invitation (pending, revoked, expired)';
  ASSERT (SELECT bool_and(email LIKE 'deleted-%@deleted.invalid') FROM invitations WHERE invited_by = '60000000-0000-0000-0000-000000000002'), 'the invitations are tombstoned';
  ASSERT (SELECT count(*) FROM notifications WHERE user_id = '60000000-0000-0000-0000-000000000001') = 0, 'the notifications are gone';
  ASSERT (SELECT count(*) FROM notification_deliveries WHERE notification_id = '60222222-0000-0000-0000-000000000021') = 0, 'and their deliveries (cascade)';
  ASSERT (SELECT count(*) FROM notification_prefs WHERE user_id = '60000000-0000-0000-0000-000000000001') = 0, 'and the preferences';
  ASSERT (SELECT count(*) FROM jobs WHERE id = '60333333-0000-0000-0000-000000000031') = 0, 'the finished job that named the person is gone';
  ASSERT (SELECT count(*) FROM jobs WHERE id = '60333333-0000-0000-0000-000000000032') = 1, 'but a live job is left (it carries ids only, not ours to drop mid-flight)';
  ASSERT (SELECT count(*) FROM event_publication WHERE completion_date IS NOT NULL AND position('60000000-0000-0000-0000-000000000001' IN serialized_event) > 0) = 0,
     'the completed event publication is gone';
  ASSERT (SELECT count(*) FROM event_publication WHERE completion_date IS NULL) = 1, 'the incomplete one is left for redelivery';
  -- the attempt data that is personal or biometric
  ASSERT (SELECT count(*) FROM speaking_recordings WHERE user_id = '60000000-0000-0000-0000-000000000001') = 0, 'the Speaking recordings'' rows are gone (the objects are deleted by PersonalDataService)';
  ASSERT (SELECT answer = 'null'::jsonb FROM responses WHERE attempt_id = '60444444-0000-0000-0000-000000000041' AND question_id = '60444444-0000-0000-0000-0000000000a1'),
     'the learner''s essay free text is scrubbed';
  ASSERT (SELECT count(*) FROM responses WHERE user_id = '60000000-0000-0000-0000-000000000001' AND answer::text ILIKE '%Hanoi%') = 0, 'no essay PII remains';
  ASSERT (SELECT comment = '' FROM teacher_reviews WHERE queue_item_id = '60444444-0000-0000-0000-000000000051'), 'the teacher''s feedback on the person''s attempt is scrubbed';
  ASSERT (SELECT bank_ref IS NULL AND status = 'CONFIRMED' FROM orders WHERE id = '60555555-0000-0000-0000-000000000062'), 'the transfer memo is cleared but the order stays for accounting';
  -- the recording keys the service would delete are available through the narrow definer (now empty, since the rows are gone)
  ASSERT (SELECT count(*) FROM personal_data_recording_keys('60000000-0000-0000-0000-000000000001')) = 0, 'no recording keys remain after erasure';
  -- a second run is harmless (the person is already a tombstone)
  PERFORM erase_personal_data('60000000-0000-0000-0000-000000000001');
END $$;

-- the append-only log still refuses deletion, erasure or not; and the teacher-review immutability trigger the definer lifted to scrub the comment is back ON
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'audit_log'::regclass AND tgname IN ('audit_log_no_change','audit_log_no_truncate')) = 2,
     'the audit log is still append-only';
  ASSERT (SELECT tgenabled <> 'D' FROM pg_trigger WHERE tgrelid = 'teacher_reviews'::regclass AND tgname = 'teacher_reviews_immutable'),
     'the teacher-review immutability trigger is re-enabled after the erasure (the definer lifted it only to scrub the comment)';
END $$;

-- a NORMAL caller (the request role) still cannot change or delete a teacher review: the trigger is on and e9x_app has no UPDATE/DELETE grant either
CREATE FUNCTION public.t_tr(stmt text) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE stmt; RETURN 'ok';
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_tr(text) TO e9x_app;
SET ROLE e9x_app;
SELECT set_config('app.user_id','60000000-0000-0000-0000-000000000003',false), set_config('app.org_id','60aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT public.t_tr($q$UPDATE teacher_reviews SET comment = 'tampered' WHERE queue_item_id = '60444444-0000-0000-0000-000000000051'$q$) <> 'ok',
     'a normal caller cannot change a teacher review (trigger on, no grant)';
  ASSERT public.t_tr($q$DELETE FROM teacher_reviews WHERE queue_item_id = '60444444-0000-0000-0000-000000000051'$q$) <> 'ok',
     'nor delete one';
END $$;
RESET ROLE;
