#!/bin/sh
# Runs before anything else in the stack (service `preflight`). On a public domain every password must have been set: the development defaults in
# docker-compose.yml are published in this repository. localhost may use them, because no port but Caddy's is published.
# Reads the values Compose resolved; prints names only, never a value.
set -u
domain="${E9X_DOMAIN:-localhost}"
case "$domain" in
  localhost|*.localhost)
    echo "preflight: E9X_DOMAIN=$domain, development defaults allowed"
    exit 0
    ;;
esac
bad=""
[ "${POSTGRES_PASSWORD:-}" = "e9x_dev_password" ] && bad="$bad POSTGRES_PASSWORD"
[ "${E9X_FLYWAY_PASSWORD:-}" = "e9x_owner_dev_password" ] && bad="$bad E9X_FLYWAY_PASSWORD"
[ "${E9X_DB_PASSWORD:-}" = "e9x_app_dev_password" ] && bad="$bad E9X_DB_PASSWORD"
[ "${E9X_WORKER_DB_PASSWORD:-}" = "e9x_worker_dev_password" ] && bad="$bad E9X_WORKER_DB_PASSWORD"
[ "${MINIO_ROOT_PASSWORD:-}" = "e9x_minio_dev_password" ] && bad="$bad MINIO_ROOT_PASSWORD"
if [ -n "$bad" ]; then
  echo "preflight: refusing to start on E9X_DOMAIN=$domain: still the development default:$bad" >&2
  echo "preflight: generate each with 'openssl rand -hex 24' and set it in infra/.env (see infra/.env.example)" >&2
  exit 1
fi
if [ "${E9X_SMTP_HOST:-}" = "mail.invalid" ]; then
  echo "preflight: refusing to start on E9X_DOMAIN=$domain: E9X_SMTP_HOST is the placeholder mail.invalid, so no account mail would ever arrive" >&2
  exit 1
fi
echo "preflight: E9X_DOMAIN=$domain, no development default in use"
