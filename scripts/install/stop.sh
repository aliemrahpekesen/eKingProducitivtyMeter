#!/usr/bin/env bash
# Stops everything scripts/install/install.sh started (backend, frontend, infra containers) while
# KEEPING all data volumes. Reinstalling later resumes with the same data.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
STATE_DIR=".install"

for app in backend frontend; do
  if [ -f "$STATE_DIR/$app.pid" ]; then
    PID="$(cat "$STATE_DIR/$app.pid")"
    if kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null || true; echo "stopped $app (pid $PID)"; fi
    rm -f "$STATE_DIR/$app.pid"
  fi
done

COMPOSE_ENV="infra/docker-compose/.env"
[ -f "$COMPOSE_ENV" ] || cp infra/docker-compose/.env.example "$COMPOSE_ENV"
docker compose -f infra/docker-compose/docker-compose.yml --env-file "$COMPOSE_ENV" \
  --profile core --profile observability stop
echo "infra stopped (volumes kept) — restart with ./scripts/install/install.sh"
