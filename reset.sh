#!/usr/bin/env bash
# ==============================================================================
# English9x: Làm sạch hệ thống và nạp lại từ đầu (Factory Reset & Re-seed)
# Cách dùng: ./reset.sh [-y]
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [ "${1:-}" != "-y" ]; then
  read -p "⚠️  CẢNH BÁO: Thao tác này sẽ xóa toàn bộ database, volumes và dựng lại hệ thống mới. Tiếp tục? (y/N): " -r CONFIRM
  if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
    echo "❌ Đã hủy thao tác reset."
    exit 0
  fi
fi

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml"

echo "🧹 [1/3] Đang dừng container và xóa toàn bộ volumes cũ..."
$COMPOSE down -v

echo "🚀 [2/3] Đang khởi động lại hệ thống sạch sẽ..."
"$ROOT/start.sh"

echo "⏳ Đợi 10 giây để backend hoàn tất chạy migrations..."
sleep 10

echo "🌱 [3/3] Đang tự động nạp lại dữ liệu mẫu..."
"$ROOT/seed.sh"

echo ""
echo "🎉 HỆ THỐNG ĐÃ ĐƯỢC RESET VÀ NẠP DỮ LIỆU MẪU MỚI THÀNH CÔNG!"
