#!/usr/bin/env bash
# Stops exactly what `make demo-up` started — the demo frontend, backend, and the Postgres service
# (on whatever ports demo-up used) — and nothing else. Keeps the DB volume (`make dev-down` wipes it).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="infra/docker-compose/.env"
COMPOSE="docker compose -f infra/docker-compose/docker-compose.yml --profile core"
[ -f "$ENV_FILE" ] && COMPOSE="$COMPOSE --env-file $ENV_FILE"

DEMO_DIR=".demo"
FRONTEND_PORT=""
BACKEND_PORT=""
# shellcheck disable=SC1091
[ -f "$DEMO_DIR/state" ] && . "$DEMO_DIR/state"

# Kill a recorded pid and its direct children (e.g. vite under pnpm).
stop_pid() {
  local pidfile="$1" label="$2" pid
  [ -f "$pidfile" ] || return 0
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    pkill -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    echo "stopped $label (pid $pid)"
  fi
  rm -f "$pidfile"
}

# Fallback: if a recorded demo port still has a listener AND it is one of OUR process types
# (node/vite/java), stop it. Scoped to the exact port demo-up recorded — never a blind port kill.
sweep_port() {
  local port="$1" pid cmd
  [ -n "$port" ] || return 0
  pid="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)"
  [ -n "$pid" ] || return 0
  cmd="$(ps -p "$pid" -o comm= 2>/dev/null || true)"
  case "$cmd" in
    *node* | *java* | *vite* | *esbuild*)
      kill "$pid" 2>/dev/null && echo "stopped stray listener on :$port (pid $pid — $cmd)" || true
      ;;
  esac
}

stop_pid "$DEMO_DIR/frontend.pid" "frontend"
stop_pid "$DEMO_DIR/backend.pid" "eip-app"
sweep_port "$FRONTEND_PORT"
sweep_port "$BACKEND_PORT"
rm -f "$DEMO_DIR/state"

echo "stopping Postgres…"
$COMPOSE stop postgres >/dev/null 2>&1 || true
echo "demo stopped. Postgres data volume kept (run 'make dev-down' to wipe it)."
