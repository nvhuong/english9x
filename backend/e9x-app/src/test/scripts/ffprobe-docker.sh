#!/usr/bin/env bash
# ffprobe for the tests. A real ffprobe on the PATH is used when there is one; otherwise the same static build the application image
# carries (infra/app.Dockerfile) runs in Docker, with the directory of the file (the last argument) mounted read-only.
# Pin: keep the digest equal to the one in infra/app.Dockerfile (MediaFfprobeImageTest compares them).
set -euo pipefail
if command -v ffprobe >/dev/null 2>&1; then exec ffprobe "$@"; fi
IMAGE="mwader/static-ffmpeg:7.1.1@sha256:11a44711684c0b9f754c047dcd64235b8b52deab251bd0e0a86f22faa160749c"
file="${!#}"
dir="$(cd "$(dirname "$file")" && pwd)"
args=("${@:1:$#-1}")
exec docker run --rm --network none -v "$dir":/in:ro --entrypoint /ffprobe "$IMAGE" "${args[@]}" "/in/$(basename "$file")"
