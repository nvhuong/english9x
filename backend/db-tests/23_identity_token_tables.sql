-- P1.4: the token tables and external identities. The request role has no access to the three token tables; it sees
-- only its own linked identities.
INSERT INTO users(id,email) VALUES
 ('00000001-0000-0000-0000-000000000001','u1@x.test'),
 ('00000002-0000-0000-0000-000000000002','u2@x.test');
INSERT INTO auth_identities(user_id,provider,subject,email) VALUES
 ('00000001-0000-0000-0000-000000000001','google','sub-1','u1@x.test'),
 ('00000002-0000-0000-0000-000000000002','google','sub-2','u2@x.test');
INSERT INTO refresh_tokens(user_id,family_id,token_hash,expires_at) VALUES
 ('00000001-0000-0000-0000-000000000001',gen_random_uuid(),'\x01',now()+interval '1 day');
INSERT INTO email_verifications(user_id,token_hash,expires_at) VALUES
 ('00000001-0000-0000-0000-000000000001','\x02',now()+interval '1 day');
INSERT INTO password_resets(user_id,token_hash,expires_at) VALUES
 ('00000001-0000-0000-0000-000000000001','\x03',now()+interval '1 day');

-- RLS is on for all four
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM pg_class WHERE relname IN ('auth_identities','refresh_tokens','email_verifications','password_resets')
            AND relrowsecurity) = 4, 'RLS enabled on all identity tables';
END $$;

SET ROLE e9x_app;
SELECT set_config('app.user_id','00000001-0000-0000-0000-000000000001',false);
DO $$
DECLARE t text;
BEGIN
  -- the owner of the rows still cannot read, write or delete the token tables directly
  FOREACH t IN ARRAY ARRAY['refresh_tokens','email_verifications','password_resets'] LOOP
    BEGIN
      EXECUTE format('SELECT count(*) FROM %I', t);
      RAISE EXCEPTION 'select on % must be refused', t;
    EXCEPTION WHEN insufficient_privilege THEN NULL; END;
    BEGIN
      EXECUTE format('DELETE FROM %I', t);
      RAISE EXCEPTION 'delete on % must be refused', t;
    EXCEPTION WHEN insufficient_privilege THEN NULL; END;
    BEGIN
      EXECUTE format('UPDATE %I SET user_id = user_id', t);
      RAISE EXCEPTION 'update on % must be refused', t;
    EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  END LOOP;
  BEGIN
    INSERT INTO refresh_tokens(user_id,family_id,token_hash,expires_at)
    VALUES ('00000001-0000-0000-0000-000000000001',gen_random_uuid(),'\x09',now()+interval '1 day');
    RAISE EXCEPTION 'insert into refresh_tokens must be refused';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;

  -- identities: only my own rows
  ASSERT (SELECT count(*) FROM auth_identities) = 1 AND (SELECT subject FROM auth_identities) = 'sub-1', 'only my identity';
  BEGIN
    INSERT INTO auth_identities(user_id,provider,subject,email)
    VALUES ('00000001-0000-0000-0000-000000000001','google','forged','u1@x.test');
    RAISE EXCEPTION 'identities are not writable by the request role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    DELETE FROM auth_identities;
    RAISE EXCEPTION 'identities are not deletable by the request role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- without a user: no identities at all
SELECT set_config('app.user_id','',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM auth_identities) = 0, 'no context -> no identities'; END $$;
