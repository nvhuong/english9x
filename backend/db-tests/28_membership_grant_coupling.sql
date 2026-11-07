-- P1.5: every organisation grant needs a membership of the same user in the same organisation, whoever writes it.
-- An ORG_ADMIN without a membership could never act (the context is refused) and must be impossible by any path.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-000000000001','u1@x.test'),
 ('a0000000-0000-0000-0000-000000000002','u2@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','B','CENTER'),
 ('cccccccc-0000-0000-0000-00000000000c','C','CENTER');

DO $$
BEGIN
  -- as the table owner (the strictest writer there is): still refused
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role)
     VALUES ('a0000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
    RAISE EXCEPTION 'an ORG_ADMIN grant without a membership must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role)
     VALUES ('a0000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER');
    RAISE EXCEPTION 'a LEARNER grant without a membership must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  -- a membership of ANOTHER organisation does not count
  INSERT INTO org_memberships(org_id,user_id) VALUES ('bbbbbbbb-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-000000000001');
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role)
     VALUES ('a0000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
    RAISE EXCEPTION 'a membership of B does not allow a grant in A';
  EXCEPTION WHEN check_violation THEN NULL; END;
  -- English9x grants (org NULL) need no membership
  INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000002',NULL,'IELTS','AUTHOR');
  -- with the membership in place, the grant is fine
  INSERT INTO org_memberships(org_id,user_id) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000001');
  INSERT INTO role_grants(user_id,org_id,exam_code,role)
   VALUES ('a0000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');

  -- the membership cannot be taken away (or moved) while a grant depends on it
  BEGIN
    DELETE FROM org_memberships WHERE org_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND user_id = 'a0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'deleting a membership with a grant must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    UPDATE org_memberships SET org_id = 'bbbbbbbb-0000-0000-0000-00000000000b'
     WHERE org_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND user_id = 'a0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'moving a membership with a grant must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  -- nor can the grant be moved to an organisation where the user has no membership
  BEGIN
    UPDATE role_grants SET org_id = 'cccccccc-0000-0000-0000-00000000000c' WHERE role = 'ORG_ADMIN';
    RAISE EXCEPTION 'moving a grant to an organisation without a membership must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  DELETE FROM org_memberships WHERE org_id = 'bbbbbbbb-0000-0000-0000-00000000000b' AND user_id = 'a0000000-0000-0000-0000-000000000001';
  -- grants first, then the membership: allowed
  DELETE FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000001';
  DELETE FROM org_memberships WHERE org_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND user_id = 'a0000000-0000-0000-0000-000000000001';
  ASSERT (SELECT count(*) FROM org_memberships) = 0;
END $$;

-- and through the real policies: the ORG_ADMIN of A writes a grant for a user who is not a member -> refused
INSERT INTO org_memberships(org_id,user_id) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000001');
INSERT INTO role_grants(user_id,org_id,exam_code,role)
 VALUES ('a0000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
SET ROLE e9x_app;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role)
     VALUES ('a0000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
    RAISE EXCEPTION 'an org admin must not grant a role to a non-member';
  EXCEPTION WHEN check_violation THEN NULL; END;
  INSERT INTO org_memberships(org_id,user_id) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000002');
  INSERT INTO role_grants(user_id,org_id,exam_code,role)
   VALUES ('a0000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER');
END $$;
