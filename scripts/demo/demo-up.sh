#!/usr/bin/env bash
# One-command local demo (`make demo-up`): Postgres + eip-app (demo profile, RLS-enforced) +
# frontend, with the FIXED demo tenant preloaded — no manual DB lookup. Data is seeded SIMULATION
# data, clearly labelled in the UI. Stop with `make demo-down`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="infra/docker-compose/.env"
[ -f "$ENV_FILE" ] || cp infra/docker-compose/.env.example "$ENV_FILE"
# Load POSTGRES_*/ports from the SAME .env that Compose uses, so the host-run backend connects to
# the DB Compose actually started (a customized .env must not desync the two).
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
COMPOSE="docker compose -f infra/docker-compose/docker-compose.yml --profile core --env-file $ENV_FILE"

DEMO_DIR=".demo"
DEMO_TENANT_ID="00000000-0000-4000-8000-0000000000de" # DemoDataSeeder.DEMO_TENANT_ID
PG_USER="${POSTGRES_USER:-eip}"
PG_DB="${POSTGRES_DB:-eip}"
PG_PW="${POSTGRES_PASSWORD:-eip_dev_pw}"
PG_PORT="${POSTGRES_PORT:-5432}"
BACKEND_URL="http://localhost:8080"
FRONTEND_URL="http://localhost:5173"
mkdir -p "$DEMO_DIR"

echo "==> [1/4] Starting Postgres (Compose)…"
$COMPOSE up -d --wait postgres

echo "==> [2/4] Provisioning the demo RLS role (eip_app, NOBYPASSRLS — RLS stays enforced)…"
$COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$PG_DB" \
  < infra/docker-compose/postgres/demo-roles.sql >/dev/null

echo "==> [3/4] Building + starting eip-app (demo profile)…"
./gradlew -q :eip-app:bootJar
JAR="$(ls backend/eip-app/build/libs/*.jar | grep -v -- '-plain' | head -1)"
EIP_DB_URL="jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}" \
  EIP_APP_DB_USER="eip_app" EIP_APP_DB_PASSWORD="eip_app_dev_pw" \
  EIP_MIGRATOR_DB_USER="$PG_USER" EIP_MIGRATOR_DB_PASSWORD="$PG_PW" \
  nohup java -jar "$JAR" --spring.profiles.active=demo >"$DEMO_DIR/backend.log" 2>&1 &
echo $! >"$DEMO_DIR/backend.pid"

printf "    waiting for the API to be healthy"
for i in $(seq 1 60); do
  if curl -fsS "$BACKEND_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    echo " — UP"
    break
  fi
  printf "."
  sleep 2
  if [ "$i" -eq 60 ]; then
    echo
    echo "backend did not become healthy — see $DEMO_DIR/backend.log"
    exit 1
  fi
done

cat <<EOF

  Demo backend is up ($BACKEND_URL) with seeded SIMULATION data.
    Demo tenant: $DEMO_TENANT_ID  (slug 'demo')
    Try:  curl -H "X-EIP-Tenant: $DEMO_TENANT_ID" $BACKEND_URL/api/v1/friction/summary

==> [4/4] Starting the frontend ($FRONTEND_URL) — the demo tenant is preloaded, no DB lookup needed.
    Open $FRONTEND_URL. Ctrl-C stops the frontend; run 'make demo-down' to stop the backend + Postgres.

EOF

pnpm --dir frontend install --frozen-lockfile >/dev/null
VITE_EIP_TENANT_ID="$DEMO_TENANT_ID" exec pnpm --dir frontend dev
