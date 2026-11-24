#!/usr/bin/env bash
# ==============================================================================
# English9x: Kiểm tra trạng thái hoạt động & sức khỏe hệ thống
# Cách dùng: ./status.sh
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

COMPOSE="docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml"

echo "=============================================================================="
echo "📊 TRẠNG THÁI CÁC DỊCH VỤ ENGLISH9X"
echo "=============================================================================="

if ! command -v docker >/dev/null 2>&1; then
  echo "❌ Lỗi: Docker chưa được cài đặt." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "⚠️  Không thể kết nối tới Docker daemon. Hãy chắc chắn rằng Docker Desktop hoặc dịch vụ Docker trên máy đang chạy." >&2
  exit 1
fi

$COMPOSE ps

echo ""
echo "=============================================================================="
echo "🏥 KIỂM TRA SỨC KHỎE (HEALTH CHECKS)"
echo "=============================================================================="

# 1. Backend API Health
if $COMPOSE ps app | grep -q "Up\|running"; then
  echo -n "  • Backend API (Spring Boot): "
  if $COMPOSE exec -T app wget -qO- --spider http://127.0.0.1:8080/actuator/health 2>/dev/null; then
    echo "✅ UP (Sẵn sàng phục vụ)"
  else
    echo "⚠️  Đang khởi động hoặc không phản hồi"
  fi
else
  echo "  • Backend API: ❌ STOPPED"
fi

# 2. Database PostgreSQL
if $COMPOSE ps postgres | grep -q "Up\|running"; then
  echo -n "  • Cơ sở dữ liệu PostgreSQL: "
  if $COMPOSE exec -T postgres pg_isready -U e9x_owner -d e9x >/dev/null 2>&1; then
    echo "✅ UP (Sẵn sàng kết nối)"

    # Thống kê nhanh dữ liệu hiện có
    echo ""
    echo "📈 THỐNG KÊ DỮ LIỆU HIỆN CÓ TRONG HỆ THỐNG:"
    $COMPOSE exec -T postgres psql -U e9x_owner -d e9x -t << 'EOF' || true
      SELECT '    - Người dùng (Users):        ' || count(*) FROM users
      UNION ALL
      SELECT '    - Khóa học (Courses):        ' || count(*) FROM courses
      UNION ALL
      SELECT '    - Đề thi (Tests):            ' || count(*) FROM tests
      UNION ALL
      SELECT '    - Bài học (Strategy Lessons):' || count(*) FROM strategy_lessons
      UNION ALL
      SELECT '    - Lượt thi (Exam Attempts):  ' || count(*) FROM exam_attempts
      UNION ALL
      SELECT '    - Gói học (Plans):           ' || count(*) FROM plans;
EOF
  else
    echo "⚠️  Không phản hồi"
  fi
else
  echo "  • Cơ sở dữ liệu PostgreSQL: ❌ STOPPED"
fi

# 3. MinIO S3
if $COMPOSE ps minio | grep -q "Up\|running"; then
  echo "  • Lưu trữ file MinIO S3:     ✅ UP (Cổng 9000 & 9001)"
else
  echo "  • Lưu trữ file MinIO S3:     ❌ STOPPED"
fi

# 4. Caddy Reverse Proxy
if $COMPOSE ps caddy | grep -q "Up\|running"; then
  echo "  • Caddy Reverse Proxy (SSL): ✅ UP (Cổng 80 & 443)"
else
  echo "  • Caddy Reverse Proxy (SSL): ❌ STOPPED"
fi

echo "=============================================================================="
