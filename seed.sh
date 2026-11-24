#!/usr/bin/env bash
# ==============================================================================
# English9x: Nạp dữ liệu mẫu (Sample Data) một chạm
# Cách dùng: ./seed.sh
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

exec "$ROOT/scripts/seed-sample-data.sh" "$@"
