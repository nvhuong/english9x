#!/usr/bin/env bash
# Full local verification (same steps as CI):
#   1. backend    mvn -B verify          (unit + Modulith + Testcontainers ITs; needs Docker)
#   2. fakes      node --test            (fake Gemini / fake Telegram behaviour)
#   3. frontend   pnpm lint, test, build
#   4. e2e        tsc --noEmit on the specs, then the gate node scripts/check-e2e-coverage.mjs
#   (opt-in) E9X_A11Y=1  also runs the Storybook axe scan (needs Playwright Chromium: `pnpm -C frontend exec playwright install chromium`);
#            CI always runs it as its own job, `frontend-a11y`.
#
# The E2E coverage gate is allowed to be incomplete until wave W10: by default it is reported as
# "coverage-progress" and does not fail the run. A broken manifest is always a failure.
# E9X_E2E_GATE=strict (W10 and CI's final gate) makes any uncovered feature fail the run.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

step() { printf '\n== %s\n' "$1"; }

step "backend: mvn -B verify"
(cd backend && mvn -B verify)

step "fakes: node --test"
node --test 'infra/fakes/**/*.test.mjs'

step "frontend: lint, test, build"
command -v pnpm >/dev/null || { echo "pnpm not found (Node 22 + pnpm 10 required)" >&2; exit 1; }
pnpm -C frontend install --frozen-lockfile
pnpm -C frontend run lint
pnpm -C frontend run test
pnpm -C frontend run build
if [ "${E9X_A11Y:-0}" = "1" ]; then
  step "frontend: storybook axe (serious/critical)"
  pnpm -C frontend run test:a11y
else
  echo "frontend: storybook axe scan SKIPPED (set E9X_A11Y=1 to run it here; CI job frontend-a11y always runs it)"
fi

step "e2e: tsc --noEmit (Playwright itself does not type-check)"
pnpm -C e2e install --frozen-lockfile
pnpm -C e2e exec tsc --noEmit

step "e2e coverage gate"
./scripts/e2e-gate.sh

printf '\nverify.sh: OK\n'
