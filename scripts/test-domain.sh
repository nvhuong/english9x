#!/usr/bin/env bash
# Compile and run backend/e9x-domain tests without network access.
# With network, prefer: (cd backend && mvn -pl e9x-domain test)  — same test sources, real JUnit 5.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.offline-build/domain"
unset JAVA_TOOL_OPTIONS
rm -rf "$OUT" && mkdir -p "$OUT/shim" "$OUT/main" "$OUT/test"
find "$ROOT/tools/offline-junit/src" -name '*.java' > "$OUT/shim.txt"
javac -d "$OUT/shim" @"$OUT/shim.txt"
MAIN_SRC=$(find "$ROOT/backend/e9x-domain/src/main/java" -name '*.java' | wc -l)
if [ "$MAIN_SRC" -gt 0 ]; then
  find "$ROOT/backend/e9x-domain/src/main/java" -name '*.java' > "$OUT/main.txt"
  javac -Xlint:all -Werror --release 21 -d "$OUT/main" @"$OUT/main.txt"
fi
find "$ROOT/backend/e9x-domain/src/test/java" -name '*.java' > "$OUT/test.txt"
if [ ! -s "$OUT/test.txt" ]; then echo "No test sources."; exit 2; fi
javac --release 21 -cp "$OUT/shim:$OUT/main" -d "$OUT/test" @"$OUT/test.txt"
java -cp "$OUT/shim:$OUT/main:$OUT/test" com.english9x.tools.OfflineRunner "$OUT/test"
