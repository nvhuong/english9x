# English9x

English test-prep platform (IELTS AC/GT + TOEIC L&R; B2C + B2B). Spec: `docs/superpowers/specs/2026-11-06-english9x-design.md`.

| Path | What |
|---|---|
| `backend/e9x-domain` | Pure-Java business core (no Spring/JPA) |
| `backend/e9x-app` | Spring Boot 3.5 + Spring Modulith; serves the React build; Flyway migrations |
| `backend/db-tests` | SQL tests for Row-Level Security (`scripts/test-db.sh`) |
| `frontend` | React 18 + TypeScript (Vite, Vitest, ESLint) |
| `e2e` | Playwright (specs F01-F58 arrive feature by feature) |
| `infra` | Docker Compose, Caddy, backup, fake Gemini / fake Telegram |

## Prerequisites
Java 21, Maven 3.9, Node 22, pnpm 10, Docker (Testcontainers and Compose).

## Verify everything
```bash
./scripts/verify.sh
```
Runs `mvn -B verify` (unit, Modulith structure, PostgreSQL 16 integration tests, backup and restore through the real scripts), the fake-server tests,
frontend `lint`, `test`, `build`, the E2E coverage gate (`scripts/check-e2e-coverage.mjs`) and `scripts/test-infra.sh` (Compose's required settings, the preflight, a real Caddy's limits).
Until wave W10 the gate is reported as `coverage-progress N/58` and does not fail the run;
`E9X_E2E_GATE=strict ./scripts/verify.sh` makes an incomplete gate fail.

## Run the stack
From a clean checkout, with ports 80 and 443 free (the first start builds the images):
```bash
cp infra/.env.example infra/.env
sed -i.bak "s/^E9X_JWT_SECRET=.*/E9X_JWT_SECRET=$(openssl rand -hex 32)/" infra/.env && rm infra/.env.bak
docker compose --env-file infra/.env -f infra/docker-compose.yml up --build -d --wait     # app, postgres, minio, caddy, backup
curl -k https://localhost/actuator/health                                                 # Caddy internal certificate (accept it in a browser, or curl -k)
```
Without `infra/.env` Compose stops at once and says which setting is missing. `infra/.env.example` explains each one and how to generate it. Its mail host, `mail.invalid`, lets the stack start and delivers nothing; to read the mail use the overlay:
```bash
# dev / E2E overlay: + mailpit (UI :8025; the app sends SMTP on the Compose network), fake-telegram (:8081), fake-gemini (:8082), fake-oidc (:8083, a fake "Google"); override with
# E9X_MAILPIT_PORT / E9X_FAKE_TELEGRAM_PORT / E9X_FAKE_GEMINI_PORT / E9X_FAKE_OIDC_PORT. Needs E9X_E2E_SEED_SECRET too (scripts/e2e.sh generates its own). The overlay also turns on Spring profile `e2e` (POST /api/test/seed/*).
docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml up --build -d
docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml down -v
```
Run the Playwright suite against the real stack with one command (clean database every run, always torn down, needs host ports 80/443 free):
```bash
./scripts/e2e.sh                          # everything; extra arguments go to Playwright, e.g. specs/_smoke.spec.ts or -g "language"
./scripts/e2e.sh --keep specs/common      # leave the stack up to debug (https://localhost, Mailpit UI), then: ./scripts/e2e.sh --down
```
Videos and traces land in `e2e/test-results`, the HTML report in `e2e/playwright-report`. It is not part of `verify.sh` (too slow); CI runs it as the non-blocking job `e2e`.

The default credentials in `infra/docker-compose.yml` are for localhost only: with `E9X_DOMAIN` set to anything else, the service `preflight` refuses to start the stack while any of them is in use, and names which. In production set
`POSTGRES_PASSWORD`, `E9X_FLYWAY_PASSWORD` (role `e9x_owner`, migrations), `E9X_DB_PASSWORD` (role `e9x_app_login`, the app), `E9X_WORKER_DB_PASSWORD` (role `e9x_worker_login`, background jobs), `MINIO_ROOT_PASSWORD`, `E9X_DOMAIN` (public host name), `E9X_GEMINI_API_KEY` in the environment
or the git-ignored `infra/.env`. Application settings (spec §15.1) are keys in `backend/e9x-app/src/main/resources/application.yml`
and can be overridden by environment variables.

Caddy accepts a request body of at most 10 MB under `/api`, 1 MB on the Telegram webhook, and 600 MB only on the two upload routes (`/api/cms/media`, `/api/cms/import`). A new upload route must be added in `infra/Caddyfile`.

### Existing volumes
No upgrade path is promised across development volumes: `down -v` starts clean. A volume from before P1.3 has its tables owned by the superuser; one taken between the merges of `V202611070002` and the lower-numbered `V202611070001` fails Flyway validation (out-of-order is off). To keep that from recurring, parallel tasks of a wave take their migration version when they merge, not when they start; the version must sort after everything already merged.

## Backup and restore
The service `backup` writes `pg-<UTC stamp>.sql.gz` to the `backups` volume at start-up and every 24h, keeping `E9X_BACKUP_RETENTION` (14), with a copy of the MinIO data beside it. The dump has no owners in it: **the login that restores it becomes the owner of everything, so it must be `e9x_owner`, on a volume prepared by `01-roles.sh`**. Restored as the superuser the app cannot start (`flyway_schema_history` is out of `e9x_owner`'s reach, measured in `BackupRestoreIT`), and `infra/backup/restore.sh` refuses it.
```bash
C="docker compose --env-file infra/.env -f infra/docker-compose.yml"
$C run --rm --entrypoint ls backup /backups                                      # pick a dump
$C stop app caddy backup
$C rm -sf postgres && docker volume rm english9x_pgdata                            # the OLD DATABASE IS GONE from here on
$C up -d --wait postgres                                                          # a new volume: 01-roles.sh runs with the passwords in infra/.env
$C run --rm --no-deps --entrypoint /usr/local/bin/restore.sh backup /backups/pg-<stamp>.sql.gz
$C up -d --wait                                                                   # the app's Flyway validates what was restored and goes on from there
```
`restore.sh` takes one file, runs in one transaction and refuses a database that already has tables. `BackupRestoreIT` (in `mvn verify`, so in `verify.sh` and CI's `backend`) backs up, restores into a fresh volume and starts the application on it. The MinIO half is a directory copy and has no drill yet: owner W10, see the plan.

## Threat model: what `e9x_app` can do
Row-Level Security and the definer functions protect against a wrong query and a missing check, not against an attacker who can run arbitrary statements as the request role. By design, one compromised statement as `e9x_app` can still read bcrypt hashes (`auth_login_lookup`), issue a reset and mint a refresh token (the other `auth_*` functions, which the pre-authentication flows need), set the `app.*` session settings that RLS reads, and write rows to `event_publication` and `jobs`. So the real boundary of the model is **parameterised SQL**: no statement is ever built from input. The tenant boundary inside that is what the leak sweep (`RlsLeakSweepIT`) tests. Secrets in the app's environment (Flyway's password, `E9X_JWT_SECRET`) are out of RLS's reach too; moving Flyway out of the app process is carried in the plan (P6.5).

## Database roles (Row-Level Security)
Flyway migrates as `e9x_owner` (owns every table); the app runs as `e9x_app_login`, which inherits `e9x_app`, owns nothing and
cannot bypass RLS (startup fails otherwise). `infra/postgres/init/01-roles.sh` creates both roles on the first start of an empty
volume (it is idempotent: on an existing volume run it by hand with `E9X_OWNER_PASSWORD` / `E9X_APP_PASSWORD` / `E9X_WORKER_PASSWORD` to rotate).
`application.yml` has no default for `E9X_DB_PASSWORD` / `E9X_FLYWAY_PASSWORD`; the app refuses to start without them.
Identity (P1.4) adds settings that also have **no default and stop the app from starting when missing**: `E9X_JWT_SECRET` (access-token signing key, at least 32 bytes: `openssl rand -hex 32`), `E9X_PUBLIC_BASE_URL` (the compose file derives it from `E9X_DOMAIN`; emailed links and the Google redirect URI are built from it, never from a request) and `E9X_SMTP_HOST` (+ `E9X_SMTP_PORT`, `E9X_SMTP_USER`, `E9X_SMTP_PASSWORD`, `E9X_MAIL_FROM`; real SMTP usually also needs `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true` and `..._STARTTLS_ENABLE=true`). Access tokens carry `credentials_version`, compared with the database on every authenticated request, so a password reset or a Google link ends them at once. Google sign-in is optional: set `E9X_GOOGLE_CLIENT_ID` and `E9X_GOOGLE_CLIENT_SECRET` (redirect URI `https://<domain>/api/auth/oauth2/callback/google`); without them the button is hidden and the route answers 404.
`E9X_AUTH_TRUSTED_PROXIES` lists the CIDRs of the reverse proxy whose `X-Forwarded-For` the login rate limit believes (the compose file trusts the private Docker ranges, where only Caddy can reach the app); empty means the header is ignored.
Background jobs (`platform.jobs`, spec §5.3) run over a separate small pool as `e9x_worker_login` (member of `e9x_worker`, no BYPASSRLS,
grants on the `jobs` table only; the pool is not a `DataSource` bean and is unreachable from HTTP). `E9X_WORKER_DB_PASSWORD` has no default
in `application.yml`; an instance with `app.worker.enabled=true` refuses to start without it (`false` = no polling, no worker pool).
On an existing database volume run `01-roles.sh` by hand once with `E9X_WORKER_PASSWORD` set before upgrading to this version.
Every transaction sets `app.user_id` / `app.org_id` / `app.e9x_staff` / `app.org_admin` with `set_config(..., true)` from the bound
`CurrentContextHolder` (`org.api`); with no context RLS returns no rows. `/v3/api-docs` is off unless `E9X_OPENAPI_ENABLED=true`.

## First SYS_ADMIN, organisations, invitations (P1.5)
There is **no default administrator** in any migration or Compose file, and no HTTP way to become one. Operations seed the first
SYS_ADMIN with the owner credentials, after that person registered and verified their email through the normal screens:
```bash
./scripts/grant-sys-admin.sh person@example.com      # runs scripts/grant-sys-admin.sql as e9x_owner inside the Compose postgres
                                                      # prints the Docker context and database first; refuses DOCKER_HOST or a
                                                      # context other than default/desktop-linux unless --allow-other-docker is passed
```
The script refuses an unknown or unverified address. From then on that SYS_ADMIN signs in, creates organisations (`/admin/organizations`),
invites English9x staff (`/admin/staff`) and the first ORG_ADMIN of each organisation by email; every later role change is made in
the screens (`/org/members`, `/admin/organizations/<id>`). Invitation links are `<E9X_PUBLIC_BASE_URL>/accept-invitation?token=...`
(single use, hashed in the database, valid `e9x.org.invitation-ttl` = 7 days; at most `e9x.org.invitation-hourly-limit` = 50 per person per hour).
The accepting account must be signed in with the invited address, verified; a different account is told so and the link stays valid.
A request names its organisation with `X-Org-Context: <uuid>` (absent = "Cá nhân"); a non-member, an unknown or a malformed value gets the same 403.
`join_codes` exists with its policies and a redeem function (`org_join_code_redeem`, verified accounts only); the endpoint and screens are P6.1 (F44).

## Audit log (P1.9)
Who did what, when and in which context (`/admin/audit-log`, SYS_ADMIN only; there is no organisation-level log). The table is append-only for
everyone, the table owner included (a trigger refuses UPDATE, DELETE and TRUNCATE); a record is written in the same transaction as the action
it records, so a rolled-back action leaves nothing and an action whose record cannot be written fails. Modules call
`audit.api.AuditLog#record(action, targetType, targetId, detail)`; the actor and context come from the session, never from the caller.
The first SYS_ADMIN grant by the script above is in the log too (`sys-admin.grant`, actor kind OPERATOR).
The log names an invitation by id and never keeps the invited address, because an append-only table could not erase it: the screen reads the address from the invitation while that exists.

## Notifications (P1.8)
In-app (always on), email and Telegram, per event and channel (`/notifications`, `/notifications/preferences`). Email goes through the same SMTP
settings as account mail (`E9X_SMTP_*`). Telegram is off until the operator creates a bot with @BotFather and sets `E9X_TELEGRAM_BOT_TOKEN`,
`E9X_TELEGRAM_BOT_USERNAME` and a random `E9X_TELEGRAM_WEBHOOK_SECRET` (1-256 characters of `A-Za-z0-9_-`), then registers the webhook once:
`curl -X POST https://api.telegram.org/bot<TOKEN>/setWebhook -d url=https://<domain>/api/telegram/webhook -d secret_token=<SECRET>`.
Delivery runs as the job `send_notification` (retry delays `e9x.jobs.send_notification.retry-delays`, default 30s,2m,10m); a failed
send never removes the in-app notification. Security mail (verify email, password reset, "already registered") is separate and ignores the preferences.
An unverified account can change no preference and link no chat (403 `email-not-verified`, the screen says why). A password reset or a Google sign-in that links an account removes the chat and any unused link token, and a sign-in that takes over an unverified account also resets the preferences: all of that may have been chosen by whoever registered the address first.

## Fakes
- Fake Gemini: `POST /v1beta/models/{model}:generateContent`; choose the response with `POST /__scenario {"scenario":"ok|error500|badSchema"}`; inspect calls at `GET /__requests`.
- Fake Telegram: `POST /bot{token}/sendMessage`; read what was sent with `GET /__messages?chat_id=`.
- Fake OIDC (a fake "Google"): a real OpenID Connect provider with an account chooser at `/authorize`, `/token` and `/jwks`, set up by `FAKE_OIDC_ISSUER`, `FAKE_OIDC_CLIENT_ID`, `FAKE_OIDC_CLIENT_SECRET`, `FAKE_OIDC_REDIRECT_URI`.

Tests: `node --test 'infra/fakes/**/*.test.mjs'`.

## Frontend development
```bash
pnpm -C frontend install && pnpm -C frontend dev   # http://localhost:5173, proxies /api and /actuator to :8080
```

## Branching
Integration branch is `release/v1`; never merge to `main` before `./scripts/verify.sh` is green and E2E F01-F58 pass.
