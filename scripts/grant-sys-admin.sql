-- Makes an EXISTING, email-verified account the English9x SYS_ADMIN. Run by the operator as the table owner (see
-- scripts/grant-sys-admin.sh and the README, "First SYS_ADMIN"); there is deliberately no HTTP way and no default account.
-- Usage (psql): psql -v ON_ERROR_STOP=1 -v email=person@example.com -f scripts/grant-sys-admin.sql
\set ON_ERROR_STOP on
begin;
select set_config('e9x.target_email', :'email', true);
do $$
declare
  v_user uuid;
  v_email text := current_setting('e9x.target_email');
  v_new integer;
begin
  select id into v_user from users where lower(email) = lower(v_email) and email_verified_at is not null;
  if v_user is null then
    raise exception 'no account with a VERIFIED email "%": the person must register and verify the address first', v_email;
  end if;
  insert into role_grants(user_id, org_id, exam_code, role) values (v_user, null, null, 'SYS_ADMIN')
    on conflict on constraint role_grants_scope_uq do nothing;
  get diagnostics v_new = row_count;
  if v_new = 1 then
    -- Audit (P1.9): done by the operator outside the application, so the record has no actor (actor_kind OPERATOR) and no context.
    -- The account is named by id, not by address (personal data does not go into the log).
    insert into audit_log(actor_kind, actor_id, org_id, action, target_type, target_id, detail)
    values ('OPERATOR', null, null, 'sys-admin.grant', 'user', v_user::text, jsonb_build_object('via', 'scripts/grant-sys-admin.sh'));
  end if;
  raise notice 'SYS_ADMIN granted to % (%)', v_email, v_user;
end $$;
commit;
