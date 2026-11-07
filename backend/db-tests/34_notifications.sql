-- P1.8: notifications are private to the person (every context), the delivery functions are the only door for the worker, link tokens are
-- single-use, and the reviewer lookup is keyed by a review. Runs as the real roles (e9x_app, e9x_worker) with the session context set by hand.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email,email_verified_at,locale) VALUES
 ('c0000000-0000-0000-0000-000000000001','a@x.test', now(), 'vi'),
 ('c0000000-0000-0000-0000-000000000002','b@x.test', now(), 'en'),
 ('c0000000-0000-0000-0000-000000000003','rev@x.test', now(), 'vi'),
 ('c0000000-0000-0000-0000-000000000004','rev-b@x.test', now(), 'vi'),
 ('c0000000-0000-0000-0000-000000000005','author@x.test', now(), 'vi');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-000000000005'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','c0000000-0000-0000-0000-000000000004');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('c0000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('c0000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('c0000000-0000-0000-0000-000000000004','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','REVIEWER'),
 ('c0000000-0000-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR');
INSERT INTO review_requests(id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('d0000000-0000-0000-0000-000000000001','item',gen_random_uuid(),'aaaaaaaa-0000-0000-0000-00000000000a','IELTS','c0000000-0000-0000-0000-000000000005','c0000000-0000-0000-0000-000000000005');

SET ROLE e9x_app;
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);

-- creation: only through the function; dedupe; a user that does not exist
DO $$
DECLARE n int; failed boolean := false;
BEGIN
  BEGIN
    INSERT INTO notifications(user_id,event_type,dedupe_key) VALUES ('c0000000-0000-0000-0000-000000000001','REVIEW_REQUESTED','direct');
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the request role cannot insert a notification directly';
  SELECT count(*) INTO n FROM notification_create('c0000000-0000-0000-0000-000000000001','REVIEW_REQUESTED','{"examCode":"IELTS"}','k1', now());
  ASSERT n = 1, 'a new notification plans the email delivery (no chat linked)';
  SELECT count(*) INTO n FROM notification_create('c0000000-0000-0000-0000-000000000001','REVIEW_REQUESTED','{"examCode":"IELTS"}','k1', now());
  ASSERT n = 0, 'the same dedupe key creates nothing';
  SELECT count(*) INTO n FROM notification_create('c0000000-0000-0000-0000-0000000000ff','REVIEW_REQUESTED','{}','k1', now());
  ASSERT n = 0, 'an unknown user is ignored';
  PERFORM notification_create('c0000000-0000-0000-0000-000000000002','CONTENT_PUBLISHED','{}','kb', now());
  failed := false;
  BEGIN PERFORM notification_create('c0000000-0000-0000-0000-000000000001','bad type','{}','k2', now());
  EXCEPTION WHEN invalid_parameter_value THEN failed := true; END;
  ASSERT failed, 'an event type of the wrong shape is refused';
END $$;

-- privacy: A sees A's own only, whatever context or flags the session claims; marking only own; only read_at changes; no delete
DO $$
DECLARE n int; failed boolean := false;
BEGIN
  SELECT count(*) INTO n FROM notifications;
  ASSERT n = 1, 'A sees exactly its own notification, got ' || n;
  PERFORM set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
  PERFORM set_config('app.e9x_staff','on',false);
  PERFORM set_config('app.org_admin','on',false);
  SELECT count(*) INTO n FROM notifications;
  ASSERT n = 1, 'a claimed staff/org context shows nobody else''s notifications';
  UPDATE notifications SET read_at = now();
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'only own rows are markable';
  BEGIN UPDATE notifications SET data = '{}'; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'only read_at is updatable';
  failed := false;
  BEGIN DELETE FROM notifications; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'no delete';
  PERFORM set_config('app.org_id','',false); PERFORM set_config('app.e9x_staff','',false); PERFORM set_config('app.org_admin','',false);
END $$;

-- preferences and links belong to their person; the token and delivery tables are not readable at all
DO $$
DECLARE n int; failed boolean := false;
BEGIN
  INSERT INTO notification_prefs(user_id,event_type,channel,enabled) VALUES ('c0000000-0000-0000-0000-000000000001','REVIEW_REQUESTED','EMAIL', false);
  BEGIN INSERT INTO notification_prefs(user_id,event_type,channel,enabled) VALUES ('c0000000-0000-0000-0000-000000000002','REVIEW_REQUESTED','EMAIL', false);
  EXCEPTION WHEN OTHERS THEN failed := true; END;
  ASSERT failed, 'cannot write another person''s preference';
  failed := false;
  BEGIN PERFORM 1 FROM telegram_link_tokens; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'tokens are unreadable';
  failed := false;
  BEGIN PERFORM 1 FROM notification_deliveries; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'deliveries are unreadable';
  failed := false;
  BEGIN INSERT INTO telegram_links(user_id,chat_id) VALUES ('c0000000-0000-0000-0000-000000000001', 1); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a link is created by consuming a token only';
  failed := false;
  BEGIN PERFORM * FROM notification_delivery_load('c0000000-0000-0000-0000-000000000001', 'EMAIL'); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the request role cannot call the worker''s functions';
END $$;

-- Telegram link: issue as A, consume once; replay, wrong and expired tokens are INVALID; another account's chat is CHAT_TAKEN
SELECT telegram_link_issue(sha256('tok-a'::bytea), now(), now() + interval '15 minutes');
DO $$
DECLARE r record; n int;
BEGIN
  SELECT * INTO r FROM telegram_link_consume(sha256('tok-a'::bytea), 777001, now());
  ASSERT r.r_status = 'LINKED' AND r.r_locale = 'vi', 'first use links';
  SELECT * INTO r FROM telegram_link_consume(sha256('tok-a'::bytea), 777002, now());
  ASSERT r.r_status = 'INVALID', 'single use';
  SELECT * INTO r FROM telegram_link_consume(sha256('never-issued'::bytea), 777002, now());
  ASSERT r.r_status = 'INVALID', 'unknown token is the same answer';
  SELECT count(*) INTO n FROM telegram_links;
  ASSERT n = 1, 'A sees its own link only';
END $$;
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000002',false);
SELECT telegram_link_issue(sha256('tok-b'::bytea), now(), now() + interval '15 minutes');
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM telegram_link_consume(sha256('tok-b'::bytea), 777001, now());
  ASSERT r.r_status = 'CHAT_TAKEN', 'a chat of another account is not taken over';
  SELECT * INTO r FROM telegram_link_consume(sha256('tok-b'::bytea), 777003, now());
  ASSERT r.r_status = 'LINKED', 'the refused token was not spent';
END $$;
SELECT set_config('app.user_id','',false);
DO $$
DECLARE failed boolean := false;
BEGIN
  BEGIN PERFORM telegram_link_issue(sha256('nobody'::bytea), now(), now() + interval '1 hour'); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'issuing a token needs a signed-in person';
END $$;

-- reviewer lookup: keyed by a review; the reviewers of that exam in that context; nobody for an unknown review
DO $$
DECLARE ids uuid[];
BEGIN
  SELECT array_agg(r_user_id) INTO ids FROM notification_reviewers_of_review('d0000000-0000-0000-0000-000000000001');
  ASSERT ids = ARRAY['c0000000-0000-0000-0000-000000000003']::uuid[], 'only the REVIEWER of org A for IELTS: ' || coalesce(ids::text, 'none');
  SELECT array_agg(r_user_id) INTO ids FROM notification_reviewers_of_review('aaaaaaaa-0000-0000-0000-00000000000a');
  ASSERT ids IS NULL, 'an organisation id is not a way in';
END $$;

RESET ROLE;
-- the worker: no table, only the two delivery functions
SET ROLE e9x_worker;
DO $$
DECLARE failed boolean := false; n int;
BEGIN
  BEGIN PERFORM 1 FROM notifications; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the worker reads no notification table';
  failed := false;
  BEGIN PERFORM 1 FROM users; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'nor users';
  SELECT count(*) INTO n FROM notification_delivery_load(gen_random_uuid(), 'EMAIL');
  ASSERT n = 0, 'an unknown notification loads nothing';
  failed := false;
  BEGIN PERFORM * FROM notification_create('c0000000-0000-0000-0000-000000000001','REVIEW_REQUESTED','{}','w', now()); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the worker cannot create notifications';
END $$;
RESET ROLE;
