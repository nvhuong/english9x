-- P5.2 (spec section 11, F40): the bank account that receives the payments, the administrator's right to decide an order, and the narrow list of
-- SYS_ADMINs the "new order" notification needs.
--
--   * payment_accounts: exactly ONE active row (partial unique index). A change deactivates the old row and inserts a new one, so what an
--     order showed earlier can be traced. Every signed-in person reads the ACTIVE account (they must pay into it: the QR carries it anyway);
--     a real SYS_ADMIN reads all and writes. No DELETE.
--   * orders: a real SYS_ADMIN may now UPDATE a PENDING_PAYMENT order to CONFIRMED or REJECTED (nothing else: the policy's WITH CHECK names the
--     two states and the column grant lists the four columns of a decision). The state CHECK of the table keeps status and dates consistent. An
--     order that is no longer pending is invisible to the UPDATE policy, so a second decision changes nothing (the service answers 409).
--   * notification_sys_admins(): the user ids that hold the SYS_ADMIN grant, nothing more. role_grants stays unreadable to the notification
--     listener (which runs with nobody bound): this is the same narrow SECURITY DEFINER door as notification_reviewers_of_review, and it answers
--     only a session with nobody bound, so a signed-in request cannot use it to list the administrators.

CREATE TABLE payment_accounts (
  id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  bank_bin     char(6)     NOT NULL CHECK (bank_bin ~ '^[0-9]{6}$'),
  account_no   text        NOT NULL CHECK (account_no ~ '^[A-Za-z0-9]{1,19}$'),
  account_name text        NOT NULL CHECK (char_length(btrim(account_name)) BETWEEN 1 AND 100),
  active       boolean     NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX payment_accounts_one_active_uq ON payment_accounts (active) WHERE active;

ALTER TABLE payment_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY payment_accounts_select ON payment_accounts FOR SELECT TO e9x_app
  USING ((active AND e9x_current_user() IS NOT NULL) OR e9x_is_sys_admin());
CREATE POLICY payment_accounts_insert ON payment_accounts FOR INSERT TO e9x_app WITH CHECK (e9x_is_sys_admin());
CREATE POLICY payment_accounts_update ON payment_accounts FOR UPDATE TO e9x_app USING (e9x_is_sys_admin()) WITH CHECK (e9x_is_sys_admin());
REVOKE ALL ON payment_accounts FROM PUBLIC;
GRANT SELECT, INSERT ON payment_accounts TO e9x_app;
GRANT UPDATE (active, updated_at) ON payment_accounts TO e9x_app;

CREATE POLICY orders_decide ON orders FOR UPDATE TO e9x_app
  USING (e9x_is_sys_admin() AND status = 'PENDING_PAYMENT')
  WITH CHECK (e9x_is_sys_admin() AND status IN ('CONFIRMED', 'REJECTED'));
GRANT UPDATE (status, confirmed_at, bank_ref, reject_reason) ON orders TO e9x_app;

CREATE FUNCTION notification_sys_admins()
  RETURNS TABLE (r_user_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT DISTINCT g.user_id FROM role_grants g
   WHERE g.role = 'SYS_ADMIN' AND g.org_id IS NULL
     AND e9x_current_user() IS NULL   -- only for a session with nobody bound (the notification listener); a signed-in request gets nothing
$$;
REVOKE ALL ON FUNCTION notification_sys_admins() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_sys_admins() TO e9x_app;
