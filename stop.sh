#!/usr/bin/env bash
# ==============================================================================
# English9x: Dừng an toàn hệ thống
# Cách dùng: ./stop.sh [-v | --volumes]
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml"

echo "🛑 Đang dừng toàn bộ các container hệ thống English9x..."

if [ "${1:-}" = "-v" ] || [ "${1:-}" = "--volumes" ]; then
  read -p "⚠️  CẢNH BÁO: Bạn đã chọn cờ -v (xóa toàn bộ dữ liệu database & storage). Tiếp tục? (y/N): " -r CONFIRM
  if [[ "$CONFIRM" =~ ^[Yy]$ ]]; then
    $COMPOSE down -v
    echo "✔ Đã dừng hệ thống và xóa sạch dữ liệu volume."
  else
    echo "❌ Hủy thao tác xóa volume."
    exit 0
  fi
else
  $COMPOSE down
  echo "✔ Toàn bộ hệ thống đã được dừng an toàn (Dữ liệu database được bảo toàn)."
fi
