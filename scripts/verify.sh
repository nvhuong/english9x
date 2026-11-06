#!/usr/bin/env bash
# Full local verification (same steps as CI):
#   1. backend    mvn -B verify          (unit + Modulith + Testcontainers ITs; needs Docker)
#   2. fakes      node --test            (fake Gemini / fake Telegram behaviour)
#   3. frontend   pnpm lint, test, build
#   4. e2e gate   node scripts/check-e2e-coverage.mjs
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

step "e2e coverage gate"
gate_json="$(node scripts/check-e2e-coverage.mjs --json || true)"
summary="$(printf '%s' "$gate_json" | node -e '
  const r = JSON.parse(require("fs").readFileSync(0, "utf8"));
  console.log([r.covered, r.total, (r.manifestProblems || []).length].join(" "));
')"
read -r covered total problems <<<"$summary"
if [ "$problems" != "0" ]; then
  node scripts/check-e2e-coverage.mjs || true
  echo "FAIL: e2e/features.json is invalid ($problems problem(s))" >&2
  exit 1
fi
if [ "$covered" = "$total" ]; then
  echo "E2E coverage gate: PASS ($covered/$total)"
elif [ "${E9X_E2E_GATE:-}" = "strict" ]; then
  echo "E2E coverage gate: FAIL ($covered/$total) [E9X_E2E_GATE=strict]" >&2
  exit 1
else
  echo "E2E coverage gate: coverage-progress $covered/$total (allowed to be incomplete until W10; E9X_E2E_GATE=strict enforces)"
fi

printf '\nverify.sh: OK\n'
