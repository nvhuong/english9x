-- P1.6 generic lifecycle guard e9x_guard_version_lifecycle(): attached by P2 to every *_versions table as
--   CREATE TRIGGER <t>_lifecycle BEFORE UPDATE OR DELETE ON <t> FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
-- (trigger arguments = columns that may change together with the move to a new status, e.g. bookkeeping timestamps). Tested here
-- on a throw-away table: a published version is immutable (UPDATE and DELETE refused), the only change left is ARCHIVED.
CREATE TABLE lifecycle_demo (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  status text NOT NULL DEFAULT 'DRAFT',
  body text NOT NULL,
  touched_at timestamptz
);
CREATE TRIGGER lifecycle_demo_guard BEFORE UPDATE OR DELETE ON lifecycle_demo
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
