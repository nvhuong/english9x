#!/usr/bin/env bash
# ==============================================================================
# English9x: Khởi động lại hệ thống
# Cách dùng: ./restart.sh [--build]
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "🔄 Đang khởi động lại hệ thống English9x..."
"$ROOT/stop.sh"
echo ""
sleep 2
"$ROOT/start.sh" "$@"
