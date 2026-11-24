#!/usr/bin/env bash
# ==============================================================================
# English9x: Khởi động toàn bộ hệ thống bằng 1 lệnh duy nhất
# Cách dùng: ./start.sh [--build]
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "=============================================================================="
echo "🚀 KHỞI ĐỘNG HỆ THỐNG ENGLISH9X (FULL-STACK PLATFORM)"
echo "=============================================================================="

# 1. Khởi tạo & kiểm tra cấu hình môi trường (.env)
"$ROOT/scripts/init-env.sh"

# 2. Kiểm tra Docker
if ! command -v docker >/dev/null 2>&1; then
  echo "❌ Lỗi: Máy tính chưa cài đặt Docker hoặc docker command không khả dụng." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "❌ Lỗi: Docker daemon chưa chạy. Hãy khởi động Docker Desktop / service docker." >&2
  exit 1
fi

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml"

# 3. Khởi động Docker containers
echo ""
echo "📦 Đang khởi động các dịch vụ (PostgreSQL, MinIO, App, Caddy, Mailpit, Fakes)..."
$COMPOSE up -d "$@"

# 4. Chờ dịch vụ Backend App và Database sẵn sàng (Healthy)
echo ""
echo "⏳ Đang kiểm tra trạng thái khởi động của các dịch vụ..."
MAX_WAIT=90
ELAPSED=0
APP_HEALTHY=false

while [ $ELAPSED -lt $MAX_WAIT ]; do
  STATUS="$($COMPOSE ps app --format '{{.Status}}' 2>/dev/null || true)"
  if echo "$STATUS" | grep -q "healthy"; then
    APP_HEALTHY=true
    break
  fi
  sleep 3
  ELAPSED=$((ELAPSED + 3))
  printf "."
done
echo ""

if [ "$APP_HEALTHY" = true ]; then
  echo "✅ Toàn bộ hệ thống đã khởi động thành công và sẵn sàng phục vụ!"
else
  echo "⚠️  Backend App đang khởi động hoặc chưa chuyển sang trạng thái healthy (đã đợi ${MAX_WAIT}s)."
  echo "👉 Bạn có thể xem log thời gian thực bằng lệnh: ./logs.sh"
fi

# 5. Bảng thông tin truy cập hệ thống
echo ""
echo "=============================================================================="
echo "🌐 BẢNG ĐIỀU KHIỂN & ĐỊA CHỈ TRUY CẬP HỆ THỐNG:"
echo "=============================================================================="
echo "  • Ứng dụng chính (Web App):     https://localhost"
echo "  • Hộp thư kiểm thử (Mailpit):   http://localhost:8025"
echo "  • Quản lý lưu trữ (MinIO S3):   http://localhost:9001  (User: minioadmin / Pass: minioadmin)"
echo "  • Fake AI (Gemini Mock):        http://localhost:8082"
echo "  • Fake Telegram Bot:            http://localhost:18081"
echo "  • Fake Google OIDC:             http://localhost:8083"
echo "=============================================================================="
echo "📋 CÁC LỆNH VẬN HÀNH NHANH:"
echo "  • Nạp dữ liệu mẫu (Sample data):  ./seed.sh"
echo "  • Kiểm tra trạng thái hệ thống:    ./status.sh"
echo "  • Xem log thời gian thực:         ./logs.sh"
echo "  • Dừng hệ thống:                  ./stop.sh"
echo "  • Khởi động lại hệ thống:         ./restart.sh"
echo "=============================================================================="
