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
Runs `mvn -B verify` (unit, Modulith structure, PostgreSQL 16 integration test), the fake-server tests,
frontend `lint`, `test`, `build`, then the E2E coverage gate (`scripts/check-e2e-coverage.mjs`).
Until wave W10 the gate is reported as `coverage-progress N/58` and does not fail the run;
`E9X_E2E_GATE=strict ./scripts/verify.sh` makes an incomplete gate fail.

## Run the stack
```bash
docker compose -f infra/docker-compose.yml up --build -d     # app, postgres, minio, caddy, backup
open https://localhost                                       # Caddy internal certificate (accept it, or curl -k)
curl -k https://localhost/actuator/health

# dev / E2E overlay: + mailpit (UI :8025; the app sends SMTP on the Compose network), fake-telegram (:8081), fake-gemini (:8082), fake-oidc (:8083, a fake "Google"); override with
# E9X_MAILPIT_PORT / E9X_FAKE_TELEGRAM_PORT / E9X_FAKE_GEMINI_PORT / E9X_FAKE_OIDC_PORT. Needs E9X_JWT_SECRET and E9X_E2E_SEED_SECRET (scripts/e2e.sh generates both). The overlay also turns on Spring profile `e2e` (POST /api/test/seed/*).
docker compose -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml up --build -d
docker compose -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml down -v
```
Run the Playwright suite against the real stack with one command (clean database every run, always torn down, needs host ports 80/443 free):
```bash
./scripts/e2e.sh                          # everything; extra arguments go to Playwright, e.g. specs/_smoke.spec.ts or -g "language"
./scripts/e2e.sh --keep specs/common      # leave the stack up to debug (https://localhost, Mailpit UI), then: ./scripts/e2e.sh --down
```
Videos and traces land in `e2e/test-results`, the HTML report in `e2e/playwright-report`. It is not part of `verify.sh` (too slow); CI runs it as the non-blocking job `e2e`.

The default credentials in `infra/docker-compose.yml` are for local use only. In production set
`POSTGRES_PASSWORD`, `E9X_FLYWAY_PASSWORD` (role `e9x_owner`, migrations), `E9X_DB_PASSWORD` (role `e9x_app_login`, the app), `E9X_WORKER_DB_PASSWORD` (role `e9x_worker_login`, background jobs), `MINIO_ROOT_PASSWORD`, `E9X_DOMAIN` (public host name), `E9X_GEMINI_API_KEY` in the environment
or a git-ignored `.env`. Application settings (spec §15.1) are keys in `backend/e9x-app/src/main/resources/application.yml`
and can be overridden by environment variables.

## Database roles (Row-Level Security)
Flyway migrates as `e9x_owner` (owns every table); the app runs as `e9x_app_login`, which inherits `e9x_app`, owns nothing and
cannot bypass RLS (startup fails otherwise). `infra/postgres/init/01-roles.sh` creates both roles on the first start of an empty
volume (it is idempotent: on an existing volume run it by hand with `E9X_OWNER_PASSWORD` / `E9X_APP_PASSWORD` / `E9X_WORKER_PASSWORD` to rotate).
`application.yml` has no default for `E9X_DB_PASSWORD` / `E9X_FLYWAY_PASSWORD`; the app refuses to start without them.
Identity (P1.4) adds settings that also have **no default and stop the app from starting when missing**: `E9X_JWT_SECRET` (access-token signing key, at least 32 bytes: `openssl rand -hex 32`), `E9X_PUBLIC_BASE_URL` (the compose file derives it from `E9X_DOMAIN`; emailed links and the Google redirect URI are built from it, never from a request) and `E9X_SMTP_HOST` (+ `E9X_SMTP_PORT`, `E9X_SMTP_USER`, `E9X_SMTP_PASSWORD`, `E9X_MAIL_FROM`; real SMTP usually also needs `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true` and `..._STARTTLS_ENABLE=true`). Google sign-in is optional: set `E9X_GOOGLE_CLIENT_ID` and `E9X_GOOGLE_CLIENT_SECRET` (redirect URI `https://<domain>/api/auth/oauth2/callback/google`); without them the button is hidden and the route answers 404.
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
`join_codes` exists with its policies and a redeem function (`org_join_code_redeem`); the endpoint and screens are P6.1 (F44).

## Notifications (P1.8)
In-app (always on), email and Telegram, per event and channel (`/notifications`, `/notifications/preferences`). Email goes through the same SMTP
settings as account mail (`E9X_SMTP_*`). Telegram is off until the operator creates a bot with @BotFather and sets `E9X_TELEGRAM_BOT_TOKEN`,
`E9X_TELEGRAM_BOT_USERNAME` and a random `E9X_TELEGRAM_WEBHOOK_SECRET` (1-256 characters of `A-Za-z0-9_-`), then registers the webhook once:
`curl -X POST https://api.telegram.org/bot<TOKEN>/setWebhook -d url=https://<domain>/api/telegram/webhook -d secret_token=<SECRET>`.
Delivery runs as the job `send_notification` (retry delays `e9x.jobs.send_notification.retry-delays`, default 30s,2m,10m); a failed
send never removes the in-app notification. Security mail (verify email, password reset, "already registered") is separate and ignores the preferences.

## Fakes
- Fake Gemini: `POST /v1beta/models/{model}:generateContent`; choose the response with `POST /__scenario {"scenario":"ok|error500|badSchema"}`; inspect calls at `GET /__requests`.
- Fake Telegram: `POST /bot{token}/sendMessage`; read what was sent with `GET /__messages?chat_id=`.

Tests: `node --test 'infra/fakes/**/*.test.mjs'`.

## Frontend development
```bash
pnpm -C frontend install && pnpm -C frontend dev   # http://localhost:5173, proxies /api and /actuator to :8080
```

## Branching
Integration branch is `release/v1`; never merge to `main` before `./scripts/verify.sh` is green and E2E F01-F58 pass.
