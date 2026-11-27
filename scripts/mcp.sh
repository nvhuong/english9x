#!/usr/bin/env bash
set -euo pipefail

# English9x Backend MCP Helper Script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
MCP_DIR="$PROJECT_ROOT/mcp"

ACTION="${1:-start}"

case "$ACTION" in
  build)
    echo "🔨 Building English9x MCP Server..."
    cd "$MCP_DIR" && pnpm run build
    echo "✅ Build complete: $MCP_DIR/dist/index.js"
    ;;
  test)
    echo "🧪 Running MCP Server Unit Tests..."
    cd "$MCP_DIR" && pnpm test
    ;;
  start)
    if [ ! -f "$MCP_DIR/dist/index.js" ]; then
      echo "📦 dist/index.js not found, building first..."
      cd "$MCP_DIR" && pnpm run build
    fi
    echo "🚀 Launching English9x MCP Server (Stdio)..."
    exec node "$MCP_DIR/dist/index.js"
    ;;
  http)
    PORT="${2:-8090}"
    if [ ! -f "$MCP_DIR/dist/index.js" ]; then
      cd "$MCP_DIR" && pnpm run build
    fi
    echo "🌐 Launching English9x MCP Server on port $PORT..."
    exec node "$MCP_DIR/dist/index.js" --port "$PORT"
    ;;
  *)
    echo "Usage: $0 {build|test|start|http [port]}"
    exit 1
    ;;
esac
