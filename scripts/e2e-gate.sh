#!/usr/bin/env bash
# E2E coverage gate wrapper shared by verify.sh and CI.
#  - selftest of the gate script and manifest validity are ALWAYS blocking;
#  - incomplete coverage is reported as "coverage-progress" and tolerated until W10,
#    unless E9X_E2E_GATE=strict (W10 and CI's final gate).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

node scripts/check-e2e-coverage.selftest.mjs

gate_json="$(node scripts/check-e2e-coverage.mjs --json || true)"
summary="$(printf '%s' "$gate_json" | node -e '
  const r = JSON.parse(require("fs").readFileSync(0, "utf8"));
  console.log([r.covered, r.total, (r.manifestProblems || []).length].join(" "));
')" || { echo "FAIL: gate script did not produce valid JSON" >&2; exit 1; }
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
