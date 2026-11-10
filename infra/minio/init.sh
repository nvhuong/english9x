#!/usr/bin/env bash
# Runs once per `compose up` (service minio-init) and exits. Idempotent: running it again changes nothing that is already right.
#   * the private media bucket exists (nothing is public: no anonymous policy is ever set);
#   * the application has its OWN key, which can read, write and delete objects in that bucket and do nothing else: not another bucket,
#     not the MinIO admin API. The root credentials stay in this container and in MinIO; the app never holds them (README, Media).
# Reads the environment, prints names and never a secret.
set -euo pipefail
: "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" "${E9X_MINIO_BUCKET:?}" "${E9X_MINIO_ACCESS_KEY:?}" "${E9X_MINIO_SECRET_KEY:?}"
export MC_CONFIG_DIR=/tmp/mc
POLICY=e9x-media-app

for i in $(seq 1 30); do
  mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 && break
  [ "$i" = 30 ] && { echo "minio-init: MinIO did not accept the root credentials in time" >&2; exit 1; }
  sleep 2
done

mc mb --ignore-existing "local/$E9X_MINIO_BUCKET"
mc anonymous set none "local/$E9X_MINIO_BUCKET" >/dev/null

cat > /tmp/policy.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketMultipartUploads"],
     "Resource": ["arn:aws:s3:::${E9X_MINIO_BUCKET}"]},
    {"Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
     "Resource": ["arn:aws:s3:::${E9X_MINIO_BUCKET}/*"]}
  ]
}
JSON
mc admin policy create local "$POLICY" /tmp/policy.json >/dev/null
mc admin user add local "$E9X_MINIO_ACCESS_KEY" "$E9X_MINIO_SECRET_KEY" >/dev/null
# attaching twice is an error in mc: attach only when the user does not hold the policy yet
info="$(mc admin user info local "$E9X_MINIO_ACCESS_KEY")"
if [[ "$info" != *"PolicyName: $POLICY"* ]]; then
  mc admin policy attach local "$POLICY" --user "$E9X_MINIO_ACCESS_KEY" >/dev/null
fi
echo "minio-init: bucket $E9X_MINIO_BUCKET ready, application key limited to it"
