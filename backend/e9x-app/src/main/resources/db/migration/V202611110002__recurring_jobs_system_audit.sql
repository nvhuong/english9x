-- P5.1: the first recurring background jobs (spec section 5.3) and the audit trail of what the system itself does (spec section 11).
--
-- 1. Recurring jobs. Every application instance may enqueue the tick of a periodic job; `dedupe_key` makes two instances (or one instance
--    twice) queue a given tick ONCE: a unique index on (type, dedupe_key) and an INSERT ... ON CONFLICT DO NOTHING. The key of a tick is
--    its time bucket (JobQueue#enqueueOnce, RecurringJobScheduler), so a restarted or second instance does not re-fire the bucket.
--    Plain jobs (dedupe_key NULL) are not affected.
-- 2. Retention. A finished job keeps its payload (which may hold user data) and its dedupe key. The worker may now DELETE finished jobs, but
--    only SUCCEEDED / FAILED ones older than one day, whatever the handler asks: the purge_finished_jobs handler deletes the ones older than
--    e9x.jobs.retention (7 days by default). A tick key is a time bucket, so a deleted key never comes back.
-- 3. SYSTEM audit actor. audit_log gets a third actor kind, SYSTEM: no person, no organisation. Only audit_log_record_system() writes it, it
--    is EXECUTE-able by the worker role only, and it accepts only the system actions listed in its body (a compromised worker cannot write
--    an arbitrary record). A handler calls it in the same worker transaction as the transition it records (audit.api.SystemAuditLog), so a
--    system change and its record stand or fall together. Each later task that lets the system change something adds its action here (CREATE
--    OR REPLACE with the longer list), in its own migration.

ALTER TABLE jobs ADD COLUMN dedupe_key text CHECK (char_length(dedupe_key) BETWEEN 1 AND 200);
CREATE UNIQUE INDEX jobs_dedupe_uq ON jobs (type, dedupe_key) WHERE dedupe_key IS NOT NULL;

GRANT DELETE ON jobs TO e9x_worker;
CREATE POLICY jobs_worker_purge ON jobs FOR DELETE TO e9x_worker
  USING (status IN ('SUCCEEDED', 'FAILED') AND finished_at IS NOT NULL AND finished_at < now() - interval '1 day');

ALTER TABLE audit_log DROP CONSTRAINT audit_log_actor_ck;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_actor_ck CHECK (
     (actor_kind = 'USER'     AND actor_id IS NOT NULL)
  OR (actor_kind = 'OPERATOR' AND actor_id IS NULL)
  OR (actor_kind = 'SYSTEM'   AND actor_id IS NULL AND org_id IS NULL));

CREATE FUNCTION audit_log_record_system(p_action text, p_target_type text, p_target_id text, p_detail jsonb)
  RETURNS void
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF p_action IS NULL OR p_action NOT IN ('order.expire') THEN
    RAISE EXCEPTION 'the system may not write the audit action %', p_action USING ERRCODE = '42501';
  END IF;
  INSERT INTO audit_log(actor_kind, actor_id, org_id, action, target_type, target_id, detail)
  VALUES ('SYSTEM', NULL, NULL, p_action, p_target_type, p_target_id, coalesce(p_detail, '{}'::jsonb));
END $$;
REVOKE ALL ON FUNCTION audit_log_record_system(text, text, text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_log_record_system(text, text, text, jsonb) TO e9x_worker;
