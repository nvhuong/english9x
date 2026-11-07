-- P1.5: who may create / see / change organisations, and the ENTERPRISE rule when the type changes.
-- A flag alone never suffices: SYS_ADMIN = the English9x context flag AND a real SYS_ADMIN grant.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-000000000001','sys@x.test'),
 ('a0000000-0000-0000-0000-000000000002','author@x.test'),
 ('a0000000-0000-0000-0000-000000000003','admin-a@x.test'),
 ('a0000000-0000-0000-0000-000000000004','learner-a@x.test'),
 ('a0000000-0000-0000-0000-000000000005','teacher-a@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000004'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000005');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('a0000000-0000-0000-0000-000000000002',NULL,'IELTS','AUTHOR'),
 ('a0000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','TEACHER');

SET ROLE e9x_app;

-- 1. an AUTHOR (staff flag on, legitimately) cannot create, see, rename or retype organisations
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO organizations(name,type) VALUES ('Mine','CENTER');
    RAISE EXCEPTION 'an AUTHOR must not create an organisation';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM organizations) = 0, 'the staff flag alone must not reveal organisations';
  UPDATE organizations SET name = 'Hacked' WHERE id = 'aaaaaaaa-0000-0000-0000-00000000000a';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'an AUTHOR must not rename an organisation';
END $$;

-- 2. a learner forging the staff flag (no SYS_ADMIN grant) cannot create or see foreign organisations
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000004',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO organizations(name,type) VALUES ('Forged','CENTER');
    RAISE EXCEPTION 'a forged staff flag must not create an organisation';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM organizations) = 1, 'a forged flag sees only the organisations the user belongs to';
END $$;

-- 3. the ORG_ADMIN of A: sees A only, cannot create, rename, retype
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000003',false), set_config('app.e9x_staff','',false),
       set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.org_admin','on',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT array_agg(name) FROM organizations) = ARRAY['Center A'], 'an org admin sees their own organisation only';
  BEGIN
    INSERT INTO organizations(name,type) VALUES ('Second','CENTER');
    RAISE EXCEPTION 'an ORG_ADMIN must not create organisations';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE organizations SET name = 'Renamed' WHERE id = 'aaaaaaaa-0000-0000-0000-00000000000a';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'an ORG_ADMIN must not rename their organisation';
  UPDATE organizations SET type = 'ENTERPRISE' WHERE id = 'aaaaaaaa-0000-0000-0000-00000000000a';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'an ORG_ADMIN must not change the type';
END $$;

-- 4. the real SYS_ADMIN: creates, sees every organisation, renames
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
DECLARE n int; new_id uuid;
BEGIN
  INSERT INTO organizations(name,type) VALUES ('Enterprise E','ENTERPRISE') RETURNING id INTO new_id;
  ASSERT new_id IS NOT NULL;
  ASSERT (SELECT count(*) FROM organizations) = 3, 'a SYS_ADMIN sees every organisation';
  UPDATE organizations SET name = 'Center A (renamed)' WHERE id = 'aaaaaaaa-0000-0000-0000-00000000000a';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'a SYS_ADMIN renames';
  BEGIN
    INSERT INTO organizations(name,type) VALUES ('Bad','SCHOOL');
    RAISE EXCEPTION 'unknown type must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

-- 5. changing the type to ENTERPRISE is refused while AUTHOR / REVIEWER / TEACHER grants exist (ledger line 62)
DO $$
BEGIN
  BEGIN
    UPDATE organizations SET type = 'ENTERPRISE' WHERE id = 'aaaaaaaa-0000-0000-0000-00000000000a';
    RAISE EXCEPTION 'A has a TEACHER grant: the retype must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  -- an org without incompatible grants may change type, in both directions
  UPDATE organizations SET type = 'ENTERPRISE' WHERE id = 'bbbbbbbb-0000-0000-0000-00000000000b';
  ASSERT (SELECT type FROM organizations WHERE id = 'bbbbbbbb-0000-0000-0000-00000000000b') = 'ENTERPRISE';
  UPDATE organizations SET type = 'CENTER' WHERE id = 'bbbbbbbb-0000-0000-0000-00000000000b';
END $$;

-- 5b. the same refusal while a PENDING invitation for those roles exists (a live link would grant TEACHER inside an
-- ENTERPRISE after the change). Revoked, expired and accepted invitations and other roles do not block it.
DO $$
DECLARE sys uuid := 'a0000000-0000-0000-0000-000000000001'; b uuid := 'bbbbbbbb-0000-0000-0000-00000000000b';
BEGIN
  INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
    VALUES (b,'t@x.test','[{"role":"TEACHER","exam":"IELTS"}]',sha256('retype-1'::bytea),sys,now()+interval '1 day');
  BEGIN
    UPDATE organizations SET type = 'ENTERPRISE' WHERE id = b;
    RAISE EXCEPTION 'a pending TEACHER invitation must block the retype';
  EXCEPTION WHEN check_violation THEN NULL; END;
  ASSERT (SELECT type FROM organizations WHERE id = b) = 'CENTER';
  UPDATE invitations SET revoked_at = now() WHERE org_id = b;
  -- an expired one does not block, nor does a compatible role
  INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
    VALUES (b,'t2@x.test','[{"role":"REVIEWER","exam":"IELTS"}]',sha256('retype-2'::bytea),sys,now()-interval '1 minute'),
           (b,'l@x.test','[{"role":"LEARNER","exam":null}]',sha256('retype-3'::bytea),sys,now()+interval '1 day');
  UPDATE organizations SET type = 'ENTERPRISE' WHERE id = b;
  ASSERT (SELECT type FROM organizations WHERE id = b) = 'ENTERPRISE', 'revoked, expired and learner invitations do not block';
END $$;
