#!/usr/bin/env bash
# Rewrites frontend/openapi/openapi.json from the running app (Testcontainers PostgreSQL; needs Docker), then
# regenerates the TypeScript client. OpenApiSnapshotIT fails when the committed snapshot is stale.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$ROOT/backend" && mvn -B -q -pl e9x-app -am verify -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=OpenApiSnapshotIT -De9x.openapi.update=true)
pnpm -C "$ROOT/frontend" run gen-api
