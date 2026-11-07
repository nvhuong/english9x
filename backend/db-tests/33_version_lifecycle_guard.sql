-- P1.6 generic lifecycle guard e9x_guard_version_lifecycle(): attached by P2 to every *_versions table as
--   CREATE TRIGGER <t>_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON <t> FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
-- (trigger arguments = columns that may change together with the move to a new status, e.g. bookkeeping timestamps). Tested here
-- on a throw-away table: a published version is immutable (UPDATE and DELETE refused), the only change left is ARCHIVED.
CREATE TABLE lifecycle_demo (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  status text NOT NULL DEFAULT 'DRAFT',
  body text NOT NULL,
  touched_at timestamptz
);
CREATE TRIGGER lifecycle_demo_guard BEFORE INSERT OR UPDATE OR DELETE ON lifecycle_demo
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('touched_at');

INSERT INTO lifecycle_demo(id,status,body) VALUES
 ('a0000000-0000-0000-0000-000000000001','DRAFT','d'),
 ('a0000000-0000-0000-0000-000000000002','IN_REVIEW','r'),
 ('a0000000-0000-0000-0000-000000000003','PUBLISHED','p'),
 ('a0000000-0000-0000-0000-000000000004','ARCHIVED','a'),
 ('a0000000-0000-0000-0000-000000000005','CHANGES_REQUESTED','c');

CREATE FUNCTION pg_temp.refused(p_sql text) RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE p_sql;
  RETURN false;
EXCEPTION WHEN integrity_constraint_violation THEN RETURN true;
END $$;

DO $$
BEGIN
  -- published: any edit, a no-op edit, a status change other than ARCHIVED, a delete: all refused
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET body='x' WHERE id='a0000000-0000-0000-0000-000000000003'$q$), 'published body edit';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET body=body WHERE id='a0000000-0000-0000-0000-000000000003'$q$), 'published no-op edit';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='DRAFT' WHERE id='a0000000-0000-0000-0000-000000000003'$q$), 'published back to draft';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='IN_REVIEW' WHERE id='a0000000-0000-0000-0000-000000000003'$q$), 'published back to review';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='ARCHIVED', body='x' WHERE id='a0000000-0000-0000-0000-000000000003'$q$), 'archiving cannot smuggle an edit';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET id=gen_random_uuid() WHERE id='a0000000-0000-0000-0000-000000000003'$q$), 'published id';
  ASSERT pg_temp.refused($q$DELETE FROM lifecycle_demo WHERE id='a0000000-0000-0000-0000-000000000003'$q$), 'published delete';
  -- archived is final
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='PUBLISHED' WHERE id='a0000000-0000-0000-0000-000000000004'$q$), 'archived back to published';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET body='x' WHERE id='a0000000-0000-0000-0000-000000000004'$q$), 'archived edit';
  ASSERT pg_temp.refused($q$DELETE FROM lifecycle_demo WHERE id='a0000000-0000-0000-0000-000000000004'$q$), 'archived delete';
  -- under review the content is frozen (what the reviewer read is what gets published)
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET body='x' WHERE id='a0000000-0000-0000-0000-000000000002'$q$), 'edit while in review';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='DRAFT' WHERE id='a0000000-0000-0000-0000-000000000002'$q$), 'review back to draft is not in the lifecycle';
  -- no shortcut to PUBLISHED
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='PUBLISHED' WHERE id='a0000000-0000-0000-0000-000000000001'$q$), 'draft straight to published';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='PUBLISHED' WHERE id='a0000000-0000-0000-0000-000000000005'$q$), 'changes-requested straight to published';
  ASSERT pg_temp.refused($q$UPDATE lifecycle_demo SET status='NONSENSE' WHERE id='a0000000-0000-0000-0000-000000000001'$q$), 'unknown status';
END $$;

DO $$
BEGIN
  -- the legal moves
  UPDATE lifecycle_demo SET body='edited' WHERE id='a0000000-0000-0000-0000-000000000001';                  -- draft is editable
  UPDATE lifecycle_demo SET body='edited' WHERE id='a0000000-0000-0000-0000-000000000005';                  -- so is changes-requested
  UPDATE lifecycle_demo SET status='IN_REVIEW' WHERE id='a0000000-0000-0000-0000-000000000001';
  UPDATE lifecycle_demo SET status='IN_REVIEW' WHERE id='a0000000-0000-0000-0000-000000000005';
  UPDATE lifecycle_demo SET status='CHANGES_REQUESTED' WHERE id='a0000000-0000-0000-0000-000000000001';
  UPDATE lifecycle_demo SET status='IN_REVIEW' WHERE id='a0000000-0000-0000-0000-000000000001';
  UPDATE lifecycle_demo SET status='PUBLISHED' WHERE id='a0000000-0000-0000-0000-000000000002';
  UPDATE lifecycle_demo SET status='ARCHIVED', touched_at=now() WHERE id='a0000000-0000-0000-0000-000000000003';
  ASSERT (SELECT status FROM lifecycle_demo WHERE id='a0000000-0000-0000-0000-000000000003') = 'ARCHIVED';
  ASSERT (SELECT body FROM lifecycle_demo WHERE id='a0000000-0000-0000-0000-000000000002') = 'r', 'the published body is the reviewed one';
  INSERT INTO lifecycle_demo(id,status,body) VALUES ('a0000000-0000-0000-0000-000000000006','DRAFT','gone'),('a0000000-0000-0000-0000-000000000007','CHANGES_REQUESTED','gone');
  DELETE FROM lifecycle_demo WHERE body = 'gone';                                                            -- a draft or a changes-requested version may go
  ASSERT (SELECT count(*) FROM lifecycle_demo WHERE body = 'gone') = 0;
  ASSERT pg_temp.refused($q$DELETE FROM lifecycle_demo WHERE status = 'IN_REVIEW'$q$), 'a version under review is not deleted';
END $$;

-- ---- INSERT: the application role starts every version as DRAFT --------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON lifecycle_demo TO e9x_app;
SET ROLE e9x_app;
DO $$
BEGIN
  ASSERT pg_temp.refused($q$INSERT INTO lifecycle_demo(status,body) VALUES ('PUBLISHED','x')$q$), 'insert PUBLISHED';
  ASSERT pg_temp.refused($q$INSERT INTO lifecycle_demo(status,body) VALUES ('IN_REVIEW','x')$q$), 'insert IN_REVIEW';
  ASSERT pg_temp.refused($q$INSERT INTO lifecycle_demo(status,body) VALUES ('ARCHIVED','x')$q$), 'insert ARCHIVED';
  BEGIN
    INSERT INTO lifecycle_demo(status,body) VALUES (NULL,'x');
    ASSERT false, 'insert NULL status';
  EXCEPTION WHEN not_null_violation OR integrity_constraint_violation THEN NULL; END;
  INSERT INTO lifecycle_demo(status,body) VALUES ('DRAFT','ok');
  INSERT INTO lifecycle_demo(body) VALUES ('default is DRAFT');
  ASSERT (SELECT count(*) FROM lifecycle_demo WHERE body IN ('ok','default is DRAFT') AND status = 'DRAFT') = 2;
END $$;
RESET ROLE;
-- every other login is refused too: the rule names who may, not who may not (an import job runs as the worker)
GRANT SELECT, INSERT ON lifecycle_demo TO e9x_worker_login;
SET ROLE e9x_worker_login;
DO $$
BEGIN
  ASSERT pg_temp.refused($q$INSERT INTO lifecycle_demo(status,body) VALUES ('PUBLISHED','x')$q$), 'worker inserts PUBLISHED';
  INSERT INTO lifecycle_demo(status,body) VALUES ('DRAFT','worker draft');
END $$;
RESET ROLE;
DO $$
BEGIN
  -- the owner / a superuser (migrations, seed data) may insert any status
  INSERT INTO lifecycle_demo(status,body) VALUES ('PUBLISHED','seeded');
  ASSERT (SELECT status FROM lifecycle_demo WHERE body = 'seeded') = 'PUBLISHED';
END $$;

-- ---- never fails open on a table without a status column ----------------------------------------------------------------------
CREATE TABLE lifecycle_nostatus (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), body text);
CREATE TRIGGER lifecycle_nostatus_guard BEFORE INSERT OR UPDATE OR DELETE ON lifecycle_nostatus
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle();
GRANT SELECT, INSERT, UPDATE, DELETE ON lifecycle_nostatus TO e9x_app;
INSERT INTO lifecycle_nostatus(body) VALUES ('seed');           -- superuser: an INSERT is the owner's business
DO $$
BEGIN
  ASSERT pg_temp.refused($q$UPDATE lifecycle_nostatus SET body='x'$q$), 'update without a status column';
  ASSERT pg_temp.refused($q$DELETE FROM lifecycle_nostatus$q$), 'delete without a status column';
END $$;
SET ROLE e9x_app;
DO $$
BEGIN
  ASSERT pg_temp.refused($q$INSERT INTO lifecycle_nostatus(body) VALUES ('x')$q$), 'insert without a status column, as the application';
END $$;
RESET ROLE;
