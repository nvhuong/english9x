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

# dev / E2E overlay: + mailpit (UI :8025; the app sends SMTP on the Compose network), fake-telegram (:8081), fake-gemini (:8082); override with
# E9X_MAILPIT_PORT / E9X_FAKE_TELEGRAM_PORT / E9X_FAKE_GEMINI_PORT. The overlay also turns on Spring profile `e2e` (POST /api/test/seed/*).
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
Background jobs (`platform.jobs`, spec §5.3) run over a separate small pool as `e9x_worker_login` (member of `e9x_worker`, no BYPASSRLS,
grants on the `jobs` table only; the pool is not a `DataSource` bean and is unreachable from HTTP). `E9X_WORKER_DB_PASSWORD` has no default
in `application.yml`; an instance with `app.worker.enabled=true` refuses to start without it (`false` = no polling, no worker pool).
On an existing database volume run `01-roles.sh` by hand once with `E9X_WORKER_PASSWORD` set before upgrading to this version.
Every transaction sets `app.user_id` / `app.org_id` / `app.e9x_staff` / `app.org_admin` with `set_config(..., true)` from the bound
`CurrentContextHolder` (`org.api`); with no context RLS returns no rows. `/v3/api-docs` is off unless `E9X_OPENAPI_ENABLED=true`.

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
