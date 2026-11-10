-- P5.1: plans, orders and the worker's two narrow doors; the SYSTEM audit actor; recurring-job dedupe.
INSERT INTO users(id,email,email_verified_at) VALUES
 ('c0000000-0000-0000-0000-000000000001','sys@x.test', now()),
 ('c0000000-0000-0000-0000-000000000002','a@x.test', now()),
 ('c0000000-0000-0000-0000-000000000003','b@x.test', now());
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('c0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status) VALUES ('c1000000-0000-0000-0000-000000000001',NULL,'IELTS','BILL01','c','PUBLISHED');
INSERT INTO plans(id,name,price_vnd,months,max_courses,active) VALUES
 ('c2000000-0000-0000-0000-000000000001','on sale',100000,1,1,true),
 ('c2000000-0000-0000-0000-000000000002','off',100000,1,1,false);

SET ROLE e9x_app;
-- a learner: sees plans on sale only, cannot write a plan, orders for themself only, cannot change or delete an order
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
DECLARE failed boolean;
BEGIN
  ASSERT (SELECT count(*) FROM plans) = 1, 'a learner sees only plans on sale';
  failed := false; BEGIN INSERT INTO plans(name,price_vnd,months,max_courses) VALUES ('x',1,1,1); EXCEPTION WHEN insufficient_privilege OR check_violation THEN failed := true; END;
  ASSERT failed, 'a learner cannot create a plan';
  UPDATE plans SET price_vnd = 1 WHERE id = 'c2000000-0000-0000-0000-000000000001';
  ASSERT (SELECT price_vnd FROM plans WHERE id = 'c2000000-0000-0000-0000-000000000001') = 100000, 'a learner''s plan update changes nothing';
  INSERT INTO orders(id,code,user_id,plan_id,plan_months,amount_vnd) VALUES ('c3000000-0000-0000-0000-000000000001','AAAAAA','c0000000-0000-0000-0000-000000000002','c2000000-0000-0000-0000-000000000001',1,100000);
  INSERT INTO order_items(order_id,user_id,course_id,course_code) VALUES ('c3000000-0000-0000-0000-000000000001','c0000000-0000-0000-0000-000000000002','c1000000-0000-0000-0000-000000000001','BILL01');
  failed := false; BEGIN INSERT INTO orders(code,user_id,plan_id,plan_months,amount_vnd) VALUES ('BBBBBB','c0000000-0000-0000-0000-000000000003','c2000000-0000-0000-0000-000000000001',1,1); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'an order for somebody else is refused';
  failed := false; BEGIN INSERT INTO orders(code,user_id,plan_id,plan_months,amount_vnd,status) VALUES ('CCCCCC','c0000000-0000-0000-0000-000000000002','c2000000-0000-0000-0000-000000000001',1,1,'CONFIRMED'); EXCEPTION WHEN insufficient_privilege OR check_violation THEN failed := true; END;
  ASSERT failed, 'a learner cannot place a CONFIRMED order';
  UPDATE orders SET status = 'CONFIRMED', confirmed_at = now();   -- P5.2 granted the decision columns, to a real SYS_ADMIN only (43_payment_accounts.sql)
  ASSERT (SELECT status FROM orders) = 'PENDING_PAYMENT', 'a learner cannot update an order';
  failed := false; BEGIN UPDATE orders SET amount_vnd = 1; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'and never the price';
  failed := false; BEGIN INSERT INTO order_items(order_id,user_id,course_id,course_code) VALUES ('c3000000-0000-0000-0000-000000000001','c0000000-0000-0000-0000-000000000003','c1000000-0000-0000-0000-000000000001','BILL01'); EXCEPTION WHEN insufficient_privilege OR foreign_key_violation THEN failed := true; END;
  ASSERT failed, 'an item cannot be filed under another person';
  ASSERT (SELECT count(*) FROM orders) = 1, 'sees own order';
  failed := false; BEGIN PERFORM audit_log_record_system('order.expire','order','x','{}'); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the request role cannot write the SYSTEM audit';
  failed := false; BEGIN PERFORM billing_expire_orders(now(), now()); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the request role cannot expire orders';
END $$;
-- another learner and the admin
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000003',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM orders) = 0 AND (SELECT count(*) FROM order_items) = 0, 'another learner sees none of it'; END $$;
SELECT set_config('app.user_id','c0000000-0000-0000-0000-000000000001',false), set_config('app.e9x_staff','on',false);
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM orders) = 1 AND (SELECT count(*) FROM plans) = 2, 'a real SYS_ADMIN reads every order and every plan';
  UPDATE plans SET price_vnd = 120000 WHERE id = 'c2000000-0000-0000-0000-000000000001';
  ASSERT (SELECT price_vnd FROM plans WHERE id = 'c2000000-0000-0000-0000-000000000001') = 120000, 'and writes plans';
END $$;
RESET ROLE;

-- the worker: functions only
SET ROLE e9x_worker_login;
DO $$
DECLARE failed boolean; n int;
BEGIN
  failed := false; BEGIN PERFORM count(*) FROM orders; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the worker has no table access to orders';
  failed := false; BEGIN PERFORM count(*) FROM plans; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'nor to plans';
  SELECT count(*) INTO n FROM billing_expire_orders(now() - interval '1 day', now());
  ASSERT n = 0, 'a fresh order does not expire';
  SELECT count(*) INTO n FROM billing_expire_orders(now() + interval '1 day', now());
  ASSERT n = 1, 'an old enough order expires';
  SELECT count(*) INTO n FROM billing_expire_orders(now() + interval '1 day', now());
  ASSERT n = 0, 'and only once';
  PERFORM audit_log_record_system('order.expire','order','c3000000-0000-0000-0000-000000000001','{"reason":"t"}');
  failed := false; BEGIN PERFORM audit_log_record_system('role.change','user','x','{}'); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the system may write only the actions listed in the function';
  failed := false; BEGIN INSERT INTO audit_log(actor_kind,action,target_type,target_id) VALUES ('SYSTEM','order.expire','order','y'); EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'and not by writing the table';
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT (SELECT status FROM orders WHERE id = 'c3000000-0000-0000-0000-000000000001') = 'EXPIRED', 'order expired';
  ASSERT (SELECT count(*) FROM audit_log WHERE actor_kind = 'SYSTEM' AND actor_id IS NULL AND org_id IS NULL AND action = 'order.expire') = 1, 'one SYSTEM record';
END $$;

-- recurring-job dedupe: one job per (type, key)
INSERT INTO jobs(id,type,payload,status,run_at,dedupe_key) VALUES (gen_random_uuid(),'expire_orders','{}','QUEUED',now(),'tick:1');
INSERT INTO jobs(id,type,payload,status,run_at,dedupe_key) VALUES (gen_random_uuid(),'expire_orders','{}','QUEUED',now(),'tick:1') ON CONFLICT DO NOTHING;
INSERT INTO jobs(id,type,payload,status,run_at) VALUES (gen_random_uuid(),'expire_orders','{}','QUEUED',now()), (gen_random_uuid(),'expire_orders','{}','QUEUED',now());
DO $$ BEGIN ASSERT (SELECT count(*) FROM jobs WHERE type = 'expire_orders') = 3, 'a tick is queued once; plain jobs are not deduplicated'; END $$;
