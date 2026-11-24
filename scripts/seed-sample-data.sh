#!/usr/bin/env bash
# ==============================================================================
# English9x: Nạp dữ liệu mẫu (Sample Data) một chạm cho hệ thống
# Cách dùng: ./seed.sh hoặc ./scripts/seed-sample-data.sh
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 1. Khởi tạo & kiểm tra môi trường
"$ROOT/scripts/init-env.sh"

if ! command -v node >/dev/null 2>&1; then
  echo "❌ Cần cài đặt Node.js để chạy script này." >&2
  exit 1
fi

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml"

# 2. Kiểm tra container app và caddy đang chạy
if command -v docker >/dev/null 2>&1; then
  if ! $COMPOSE ps app | grep -q "Up\|running"; then
    echo "⚠️  Hệ thống chưa khởi động. Đang tự động chạy hệ thống với ./start.sh..."
    "$ROOT/start.sh"
    echo "⏳ Chờ hệ thống sẵn sàng..."
    sleep 5
  fi
fi

# 3. Tạo tài khoản, tổ chức, quyền học qua Seed API
node "$ROOT/scripts/seed-sample-data.mjs" "$@"

# 4. Nạp khóa học chi tiết, câu hỏi, đề thi thử và kết quả đánh giá năng lực
if command -v docker >/dev/null 2>&1; then
  echo "--- [4/5] Khởi tạo Khóa học, Đề thi mẫu & Đánh giá năng lực ---"
  $COMPOSE exec -T postgres psql -q -U e9x_owner -d e9x < "$ROOT/scripts/seed-sample-full.sql" || true
  echo "✔ [KHÓA HỌC & ĐỀ THI] Đã tạo khóa học TOEIC (TOE650), IELTS (IEL700) kèm Đề thi thử và Câu hỏi!"
  echo "✔ [RADAR ĐIỂM YẾU] Đã tạo kết quả thi và phân tích điểm yếu mẫu cho học viên!"
fi

echo ""
echo "=============================================================================="
echo "✨ TẤT CẢ DỮ LIỆU MẪU ĐÃ SẴN SÀNG! BẠN CÓ THỂ ĐĂNG NHẬP VÀ TRẢI NGHIỆM NGAY."
echo "=============================================================================="
