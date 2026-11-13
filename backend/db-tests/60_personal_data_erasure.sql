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

DO $$
BEGIN
  ASSERT has_function_privilege('e9x_app','erase_personal_data(uuid)','EXECUTE'), 'the request role may run the erasure';
  ASSERT NOT has_function_privilege('public','erase_personal_data(uuid)','EXECUTE'), 'PUBLIC was revoked';
  ASSERT (SELECT prosecdef AND proconfig::text LIKE '%search_path=pg_catalog, public, pg_temp%' FROM pg_proc WHERE proname = 'erase_personal_data'),
     'the erasure is a definer with a pinned search_path';
  ASSERT NOT has_table_privilege('e9x_app','audit_log','DELETE') AND NOT has_table_privilege('e9x_app','audit_log','UPDATE'),
     'erasure gives the request role no new right over the append-only log';
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
  -- a second run is harmless (the person is already a tombstone)
  PERFORM erase_personal_data('60000000-0000-0000-0000-000000000001');
END $$;

-- the append-only log still refuses deletion, erasure or not
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'audit_log'::regclass AND tgname IN ('audit_log_no_change','audit_log_no_truncate')) = 2,
     'the audit log is still append-only';
END $$;
