#!/usr/bin/env bash
# Stops the demo backend (started by demo-up.sh) and the Postgres service. Keeps the data volume
# (use `make dev-down` to wipe it).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="infra/docker-compose/.env"
COMPOSE="docker compose -f infra/docker-compose/docker-compose.yml --profile core"
[ -f "$ENV_FILE" ] && COMPOSE="$COMPOSE --env-file $ENV_FILE"

DEMO_DIR=".demo"
if [ -f "$DEMO_DIR/backend.pid" ]; then
  PID="$(cat "$DEMO_DIR/backend.pid")"
  if kill "$PID" 2>/dev/null; then
    echo "stopped eip-app (pid $PID)"
  fi
  rm -f "$DEMO_DIR/backend.pid"
fi

echo "stopping Postgres…"
$COMPOSE stop postgres >/dev/null 2>&1 || true
echo "demo stopped. Postgres data volume kept (run 'make dev-down' to wipe it)."
