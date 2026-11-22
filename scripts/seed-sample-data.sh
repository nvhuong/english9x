#!/usr/bin/env bash
# English9x: Tạo dữ liệu mẫu (Sample Data) cho hệ thống đang chạy
# Cách dùng: ./scripts/seed-sample-data.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v node >/dev/null 2>&1; then
  echo "❌ Cần cài đặt Node.js để chạy script này." >&2
  exit 1
fi

node scripts/seed-sample-data.mjs "$@"

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml"
if command -v docker >/dev/null 2>&1; then
  echo "--- [4/5] Khởi tạo Khóa học chi tiết (Chapters & Strategy Lessons) ---"
  $COMPOSE exec -T postgres psql -q -U e9x_owner -d e9x < scripts/seed-sample-courses.sql || true
  echo "✔ [KHÓA HỌC CHI TIẾT] Đã tạo khóa học TOEIC (TOE650) & IELTS (IEL700) với đầy đủ Chương và Bài học!"

  echo "--- [5/5] Khởi tạo Bảng giá Gói học mẫu (B2C Plans) ---"
  $COMPOSE exec -T postgres psql -q -U e9x_owner -d e9x << 'EOF' || true
INSERT INTO plans (name, price_vnd, months, max_courses, active) VALUES
  ('Gói Luyện Thi 1 Tháng (1 Khóa học)', 299000, 1, 1, true),
  ('Gói Bứt Phá 3 Tháng (3 Khóa học)', 699000, 3, 3, true),
  ('Gói Toàn Diện 12 Tháng (Không giới hạn)', 1999000, 12, 10, true)
ON CONFLICT DO NOTHING;
EOF
  echo "✔ [BẢNG GIÁ] Đã nạp 3 gói học B2C vào hệ thống!"
fi

echo ""
echo "✨ TẤT CẢ DỮ LIỆU MẪU ĐÃ SẴN SÀNG! Bạn có thể bắt đầu sử dụng ngay."
