#!/usr/bin/env bash
# ==============================================================================
# English9x: Rebuild lại toàn bộ hệ thống (Frontend + Backend Docker Image)
# Cách dùng: ./build.sh
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "=============================================================================="
echo "🔨 ĐANG BIÊN DỊCH VÀ XÂY DỰNG LẠI HỆ THỐNG ENGLISH9X (REBUILD)"
echo "=============================================================================="

# 1. Khởi tạo & kiểm tra cấu hình môi trường (.env)
"$ROOT/scripts/init-env.sh"

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml"

echo "📦 Đang rebuild Docker images (Frontend React + Spring Boot Backend)..."
$COMPOSE build "$@"

echo "🚀 Đang áp dụng image mới vào các container đang chạy..."
$COMPOSE up -d "$@"

echo ""
echo "✅ Quá trình rebuild hoàn tất! Giao diện và backend mới đã được cập nhật."
echo "👉 Truy cập: https://localhost (Nhấn Ctrl+Shift+R để làm mới cache trình duyệt)"
