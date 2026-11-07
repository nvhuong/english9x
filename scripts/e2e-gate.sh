#!/usr/bin/env bash
# E2E coverage gate wrapper shared by verify.sh and CI.
#  - selftest of the gate script and manifest / partial-list validity are ALWAYS blocking;
#  - incomplete coverage (features that are missing or PARTIAL, i.e. listed in e2e/partial-features.json) is reported as
#    "coverage-progress", with covered and partial counted separately, and tolerated until W10,
#    unless E9X_E2E_GATE=strict (W10 and CI's final gate), which fails while any feature is missing or partial.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

node scripts/check-e2e-coverage.selftest.mjs
node scripts/check-e2e-coverage.mjs --gate
