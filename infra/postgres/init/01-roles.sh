#!/usr/bin/env bash
# Creates the database roles of the RLS model (P1.3). Idempotent: re-running rotates the two passwords.
# Runs once on an empty data directory (docker-entrypoint-initdb.d). On an existing volume run it by hand:
#   docker compose exec -e E9X_OWNER_PASSWORD=... -e E9X_APP_PASSWORD=... postgres bash /docker-entrypoint-initdb.d/01-roles.sh
#
#   e9x_owner      LOGIN  owns the database and every table; used only by Flyway. Not a superuser.
#   e9x_app_login  LOGIN  the application runtime login; inherits e9x_app, owns nothing, never bypasses RLS.
#   e9x_app        NOLOGIN privilege group the migrations GRANT to.
#   e9x_grader     NOLOGIN scoring role (P4 connects through its own login later).
set -euo pipefail
: "${E9X_OWNER_PASSWORD:?E9X_OWNER_PASSWORD is required}"
: "${E9X_APP_PASSWORD:?E9X_APP_PASSWORD is required}"
: "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"

psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v db="$POSTGRES_DB" <<'SQL'
\getenv owner_pw E9X_OWNER_PASSWORD
\getenv app_pw E9X_APP_PASSWORD

SELECT 'CREATE ROLE e9x_app NOLOGIN NOSUPERUSER NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_app') \gexec
SELECT 'CREATE ROLE e9x_grader NOLOGIN NOSUPERUSER NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_grader') \gexec
SELECT 'CREATE ROLE e9x_owner LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_owner') \gexec
SELECT 'CREATE ROLE e9x_app_login LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB INHERIT'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_app_login') \gexec

ALTER ROLE e9x_owner LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'owner_pw';
ALTER ROLE e9x_app_login LOGIN NOSUPERUSER NOBYPASSRLS INHERIT PASSWORD :'app_pw';
GRANT e9x_app TO e9x_app_login;
ALTER DATABASE :"db" OWNER TO e9x_owner;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO e9x_owner, e9x_app_login;
SQL
