-- P5.2: the payment account, the administrator's decision on an order, and the narrow list of SYS_ADMINs for the "new order" notice.
INSERT INTO users(id,email,email_verified_at) VALUES
 ('d0000000-0000-0000-0000-000000000001','sys@x.test', now()),
 ('d0000000-0000-0000-0000-000000000002','learner@x.test', now()),
 ('d0000000-0000-0000-0000-000000000003','author@x.test', now());
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('d0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('d0000000-0000-0000-0000-000000000003',NULL,'IELTS','AUTHOR');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status) VALUES ('d1000000-0000-0000-0000-000000000001',NULL,'IELTS','PAY001','c','PUBLISHED');
INSERT INTO plans(id,name,price_vnd,months,max_courses,active) VALUES ('d2000000-0000-0000-0000-000000000001','p',100000,1,1,true);
INSERT INTO orders(id,code,user_id,plan_id,plan_months,amount_vnd) VALUES
 ('d3000000-0000-0000-0000-000000000001','PAYAAA','d0000000-0000-0000-0000-000000000002','d2000000-0000-0000-0000-000000000001',1,100000),
 ('d3000000-0000-0000-0000-000000000002','PAYBBB','d0000000-0000-0000-0000-000000000002','d2000000-0000-0000-0000-000000000001',1,100000);
INSERT INTO payment_accounts(id,bank_bin,account_no,account_name,active) VALUES
 ('d4000000-0000-0000-0000-000000000001','970436','111111','old',false),
 ('d4000000-0000-0000-0000-000000000002','970415','222222','current',true);

SET ROLE e9x_app;
-- a learner: reads only the ACTIVE account, writes nothing, cannot decide even their own order
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
DECLARE failed boolean; n int;
BEGIN
  ASSERT (SELECT count(*) FROM payment_accounts) = 1 AND (SELECT account_no FROM payment_accounts) = '222222', 'a learner sees only the active account';
  failed := false; BEGIN INSERT INTO payment_accounts(bank_bin,account_no,account_name,active) VALUES ('970436','333333','x',false); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a learner cannot add an account';
  UPDATE payment_accounts SET active = false;
  ASSERT (SELECT count(*) FROM payment_accounts) = 1, 'a learner cannot retire the account (the update sees no row)';
  UPDATE orders SET status = 'CONFIRMED', confirmed_at = now() WHERE code = 'PAYAAA';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a learner cannot confirm their own order';
  ASSERT (SELECT status FROM orders WHERE code = 'PAYAAA') = 'PENDING_PAYMENT', 'and it is unchanged';
  INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id) SELECT 'd0000000-0000-0000-0000-000000000002', id, now(), now() + interval '1 day', 'd3000000-0000-0000-0000-000000000001' FROM courses LIMIT 0;
  failed := false; BEGIN INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id) VALUES ('d0000000-0000-0000-0000-000000000002','d1000000-0000-0000-0000-000000000001', now(), now() + interval '1 day', 'd3000000-0000-0000-0000-000000000001'); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a learner cannot give themself an entitlement';
  SELECT count(*) INTO n FROM notification_sys_admins();
  ASSERT n = 0, 'a signed-in request cannot list the administrators';
END $$;

-- staff of another kind (an AUTHOR): the flag is on, the SYS_ADMIN grant is missing
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000003',false), set_config('app.e9x_staff','on',false);
DO $$
DECLARE n int; failed boolean;
BEGIN
  UPDATE orders SET status = 'CONFIRMED', confirmed_at = now() WHERE code = 'PAYAAA';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'an AUTHOR cannot confirm an order';
  ASSERT (SELECT count(*) FROM orders) = 0, 'nor read it';
  failed := false; BEGIN INSERT INTO payment_accounts(bank_bin,account_no,account_name,active) VALUES ('970436','333333','x',false); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'nor add an account';
END $$;

-- the real SYS_ADMIN decides
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int; failed boolean;
BEGIN
  ASSERT (SELECT count(*) FROM payment_accounts) = 2, 'a SYS_ADMIN reads every account';
  failed := false; BEGIN INSERT INTO payment_accounts(bank_bin,account_no,account_name,active) VALUES ('970436','333333','x',true); EXCEPTION WHEN unique_violation THEN failed := true; END;
  ASSERT failed, 'a second active account is refused by the database';
  failed := false; BEGIN INSERT INTO payment_accounts(bank_bin,account_no,account_name,active) VALUES ('97043','333333','x',false); EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a BIN is six digits';
  UPDATE payment_accounts SET active = false, updated_at = now() WHERE active;
  INSERT INTO payment_accounts(bank_bin,account_no,account_name) VALUES ('970436','333333','new');
  ASSERT (SELECT count(*) FROM payment_accounts WHERE active) = 1, 'swap: one active again';
  failed := false; BEGIN UPDATE payment_accounts SET account_no = '999999' WHERE active; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'an account number is never edited in place (only active and updated_at are updatable)';

  UPDATE orders SET status = 'CONFIRMED', confirmed_at = now(), bank_ref = 'FT1' WHERE code = 'PAYAAA' AND status = 'PENDING_PAYMENT';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'a SYS_ADMIN confirms a pending order';
  UPDATE orders SET status = 'CONFIRMED', confirmed_at = now() WHERE code = 'PAYAAA';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a confirmed order is no longer updatable: a second decision changes nothing';
  UPDATE orders SET status = 'REJECTED', reject_reason = 'late' WHERE code = 'PAYAAA';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'nor can it be rejected afterwards';
  failed := false; BEGIN UPDATE orders SET status = 'EXPIRED', expired_at = now() WHERE code = 'PAYBBB'; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'expiring is the system''s, not an administrator''s (the check names two states)';
  failed := false; BEGIN UPDATE orders SET amount_vnd = 1 WHERE code = 'PAYBBB'; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the price locked on an order is never updatable';
  failed := false; BEGIN UPDATE orders SET user_id = 'd0000000-0000-0000-0000-000000000003' WHERE code = 'PAYBBB'; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'nor the owner';
  failed := false; BEGIN UPDATE orders SET status = 'REJECTED' WHERE code = 'PAYBBB'; EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a rejection without a reason breaks the state check';
  UPDATE orders SET status = 'REJECTED', reject_reason = 'amount mismatch' WHERE code = 'PAYBBB';
  INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id) VALUES ('d0000000-0000-0000-0000-000000000002','d1000000-0000-0000-0000-000000000001', now(), now() + interval '1 month', 'd3000000-0000-0000-0000-000000000001');
  SELECT count(*) INTO n FROM notification_sys_admins();
  ASSERT n = 0, 'even an administrator''s request session gets nothing from the list (it answers the unbound listener only)';
END $$;

-- the listener: nobody bound
SELECT set_config('app.user_id','',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM notification_sys_admins()) = 1 AND (SELECT r_user_id FROM notification_sys_admins()) = 'd0000000-0000-0000-0000-000000000001',
         'with nobody bound the list is the SYS_ADMIN grant holders and nobody else (not the AUTHOR, not the learner)';
  ASSERT (SELECT count(*) FROM payment_accounts) = 0, 'and an unbound session reads no account';
  ASSERT (SELECT count(*) FROM role_grants) = 0, 'the function does not widen role_grants for the caller';
END $$;
RESET ROLE;

-- the worker has no door to either
SET ROLE e9x_worker_login;
DO $$
DECLARE failed boolean;
BEGIN
  failed := false; BEGIN PERFORM notification_sys_admins(); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the worker cannot list administrators';
  failed := false; BEGIN PERFORM count(*) FROM payment_accounts; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'nor read the payment account';
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT (SELECT status FROM orders WHERE code = 'PAYAAA') = 'CONFIRMED' AND (SELECT status FROM orders WHERE code = 'PAYBBB') = 'REJECTED', 'the decisions stood';
  ASSERT (SELECT proconfig FROM pg_proc WHERE proname = 'notification_sys_admins')::text LIKE '%pg_catalog%pg_temp%', 'search_path is pinned';
  ASSERT NOT has_function_privilege('public', 'notification_sys_admins()', 'EXECUTE'), 'not executable by PUBLIC';
END $$;
