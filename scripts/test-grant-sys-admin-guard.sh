#!/usr/bin/env bash
# What scripts/grant-sys-admin.sh does before it writes, with a stub in place of docker: no container or daemon involved.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STUB="$(mktemp -d)"
trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/docker" <<'STUB_EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_LOG"
case "$1 ${2:-}" in
  "context show") echo "${DOCKER_CONTEXT:-${STUB_CONTEXT:-default}}" ;;
  "context inspect") echo "stub://${DOCKER_CONTEXT:-${STUB_CONTEXT:-default}}" ;;
  compose*)
    case "$*" in
      *"-f -"*) cat >> "$STUB_LOG"; echo "wrote" >> "$STUB_LOG" ;;
      *) printf 'e9x_stubdb' ;;
    esac ;;
esac
STUB_EOF
chmod +x "$STUB/docker"
export STUB_LOG="$STUB/log"
fail=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; echo "     output: ${out:-}"; fail=1; fi; }
run() { : > "$STUB_LOG"; out="$(env -u DOCKER_HOST -u DOCKER_CONTEXT -u STUB_CONTEXT PATH="$STUB:$PATH" "$@" bash "$ROOT/scripts/grant-sys-admin.sh" ${args:-a@example.test} 2>&1)"; rc=$?; }
wrote() { grep -q '^wrote$' "$STUB_LOG"; }
composed() { grep -q 'docker compose' "$STUB_LOG"; }

args=""; run STUB_CONTEXT=default
check "default context: writes, after naming the context and the database" '[ $rc = 0 ] && wrote && [[ "$out" == *"default"* ]] && [[ "$out" == *"e9x_stubdb"* ]]'
check "the database is read before the write" '[ "$(grep -n "docker compose" "$STUB_LOG" | head -1 | cut -d: -f1)" -lt "$(grep -n "\-f -" "$STUB_LOG" | head -1 | cut -d: -f1)" ]'

run STUB_CONTEXT=desktop-linux
check "Docker Desktop's own local context counts as local" '[ $rc = 0 ] && wrote && [[ "$out" == *"desktop-linux"* ]]'

run DOCKER_HOST=tcp://prod.example:2376
check "DOCKER_HOST is refused, nothing touched" '[ $rc != 0 ] && ! composed && [[ "$out" == *"tcp://prod.example:2376"* ]]'

run DOCKER_CONTEXT=prod
check "another context through DOCKER_CONTEXT is refused, nothing touched" '[ $rc != 0 ] && ! composed && [[ "$out" == *"prod"* ]]'

run STUB_CONTEXT=prod
check "another active context is refused, nothing touched" '[ $rc != 0 ] && ! composed'

args="--allow-other-docker a@example.test"; run DOCKER_CONTEXT=prod
check "the explicit flag lets it through and still names the target" '[ $rc = 0 ] && wrote && [[ "$out" == *"prod"* ]] && [[ "$out" == *"e9x_stubdb"* ]]'

args="--allow-other-docker a@example.test"; run DOCKER_HOST=tcp://prod.example:2376
check "the flag covers DOCKER_HOST and names it" '[ $rc = 0 ] && wrote && [[ "$out" == *"tcp://prod.example:2376"* ]]'

args="not-an-email"; run
check "a bad address is still a usage error" '[ $rc = 2 ] && ! composed'
args="--no-such-flag a@example.test"; run
check "an unknown flag is a usage error" '[ $rc = 2 ] && ! composed'
exit $fail
