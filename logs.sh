#!/usr/bin/env bash
# ==============================================================================
# English9x: Xem log thời gian thực của các dịch vụ
# Cách dùng:
#   ./logs.sh          (Mặc định xem log của Backend App)
#   ./logs.sh app      (Xem log Backend Spring Boot)
#   ./logs.sh caddy    (Xem log Reverse Proxy & Frontend)
#   ./logs.sh postgres (Xem log Database)
#   ./logs.sh minio    (Xem log S3 Storage)
#   ./logs.sh all      (Xem log tất cả dịch vụ)
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml"
TARGET="${1:-app}"

if [ "$TARGET" = "all" ]; then
  echo "📜 Đang hiển thị log của toàn bộ hệ thống (Ctrl+C để thoát)..."
  $COMPOSE logs -f --tail=100
else
  echo "📜 Đang hiển thị log dịch vụ [${TARGET}] (Ctrl+C để thoát)..."
  $COMPOSE logs -f --tail=100 "$TARGET"
fi
