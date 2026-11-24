#!/usr/bin/env bash
# ==============================================================================
# English9x: Tự động khởi tạo & kiểm tra file cấu hình môi trường infra/.env
# ==============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/infra/.env"
ENV_EXAMPLE="$ROOT/infra/.env.example"

echo "⚙️  Kiểm tra cấu hình môi trường (infra/.env)..."

# 1. Tạo file infra/.env nếu chưa tồn tại
if [ ! -f "$ENV_FILE" ]; then
  echo "📋 File infra/.env chưa có, đang tạo từ infra/.env.example..."
  cp "$ENV_EXAMPLE" "$ENV_FILE"
fi

# Helper để cập nhật hoặc thêm biến vào .env
set_env_var() {
  local key="$1"
  local val="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    local current
    current="$(grep "^${key}=" "$ENV_FILE" | head -n1 | cut -d'=' -f2-)"
    if [ -z "$current" ]; then
      sed -i "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
    fi
  else
    echo "${key}=${val}" >> "$ENV_FILE"
  fi
}

# 2. Đảm bảo E9X_JWT_SECRET có giá trị >= 32 bytes hex
JWT_CUR="$(grep "^E9X_JWT_SECRET=" "$ENV_FILE" 2>/dev/null | cut -d'=' -f2- || true)"
if [ -z "$JWT_CUR" ]; then
  NEW_JWT="$(openssl rand -hex 32)"
  set_env_var "E9X_JWT_SECRET" "$NEW_JWT"
  echo "🔑 Đã tự động sinh E9X_JWT_SECRET (32 bytes)."
fi

# 3. Đảm bảo E9X_E2E_SEED_SECRET có giá trị >= 16 bytes
SEED_CUR="$(grep "^E9X_E2E_SEED_SECRET=" "$ENV_FILE" 2>/dev/null | cut -d'=' -f2- || true)"
if [ -z "$SEED_CUR" ]; then
  NEW_SEED="$(openssl rand -hex 24)"
  set_env_var "E9X_E2E_SEED_SECRET" "$NEW_SEED"
  echo "🔑 Đã tự động sinh E9X_E2E_SEED_SECRET (24 bytes)."
fi

# 4. Tránh đụng port 8081 của host (Fake Telegram port)
set_env_var "E9X_FAKE_TELEGRAM_PORT" "18081"

# 5. Thiết lập SMTP host mặc định cho dev overlay
SMTP_CUR="$(grep "^E9X_SMTP_HOST=" "$ENV_FILE" 2>/dev/null | cut -d'=' -f2- || true)"
if [ -z "$SMTP_CUR" ] || [ "$SMTP_CUR" = "mail.invalid" ]; then
  set_env_var "E9X_SMTP_HOST" "mailpit"
fi

echo "✅ File cấu hình infra/.env đã sẵn sàng!"
