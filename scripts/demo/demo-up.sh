#!/usr/bin/env bash
# One-command local demo (`make demo-up`): Postgres + eip-app (demo profile, RLS-enforced via a
# NOBYPASSRLS `eip_app` role) + frontend, with the FIXED demo tenant preloaded — no manual DB lookup.
# Seeded SIMULATION data only; clearly labelled in the UI. Stop with `make demo-down`.
#
# Ports are overridable (shell env > infra/docker-compose/.env > default):
#   POSTGRES_PORT (5432) · EIP_BACKEND_PORT|BACKEND_PORT (8080) · VITE_PORT|FRONTEND_PORT (5173)
# The frontend dev-proxy target follows the backend port automatically.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="infra/docker-compose/.env"
[ -f "$ENV_FILE" ] || cp infra/docker-compose/.env.example "$ENV_FILE"
DEMO_DIR=".demo"
DEMO_TENANT_ID="00000000-0000-4000-8000-0000000000de" # DemoDataSeeder.DEMO_TENANT_ID
mkdir -p "$DEMO_DIR"

# Resolve a value with precedence: shell env > .env file > default.
envget() {
  local key="$1" def="$2" val
  val="$(printenv "$key" 2>/dev/null || true)"
  if [ -z "${val:-}" ] && [ -f "$ENV_FILE" ]; then
    val="$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
  fi
  printf '%s' "${val:-$def}"
}

POSTGRES_PORT="$(envget POSTGRES_PORT 5432)"
POSTGRES_USER="$(envget POSTGRES_USER eip)"
POSTGRES_PASSWORD="$(envget POSTGRES_PASSWORD eip_dev_pw)"
POSTGRES_DB="$(envget POSTGRES_DB eip)"
BACKEND_PORT="$(envget EIP_BACKEND_PORT "$(envget BACKEND_PORT 8080)")"
FRONTEND_PORT="$(envget VITE_PORT "$(envget FRONTEND_PORT 5173)")"
# Export the DB vars so Compose (which also reads --env-file) uses the resolved values in sync.
export POSTGRES_PORT POSTGRES_USER POSTGRES_PASSWORD POSTGRES_DB

COMPOSE="docker compose -f infra/docker-compose/docker-compose.yml --profile core --env-file $ENV_FILE"

# --- fail fast on occupied ports, with the exact override example -----------------------------
port_holder() { lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print $1" (pid "$2")"; exit}'; }
conflict=0
check_port() { # port label overrideVar exampleVal
  local who
  who="$(port_holder "$1" || true)" # a free port makes lsof exit 1 (pipefail) — never fatal here
  if [ -n "$who" ]; then
    echo "  ✗ $2 port $1 is in use by: $who"
    echo "      → free it, or override:  $3=$4 make demo-up"
    conflict=1
  fi
}
echo "==> Checking ports…"
check_port "$POSTGRES_PORT" "Postgres" POSTGRES_PORT 55433
check_port "$BACKEND_PORT" "Backend" EIP_BACKEND_PORT 18080
check_port "$FRONTEND_PORT" "Frontend" VITE_PORT 5174
if [ "$conflict" -ne 0 ]; then
  echo
  echo "  Refusing to start. Free the port(s) above, or re-run with overrides, e.g.:"
  echo "      POSTGRES_PORT=55433 EIP_BACKEND_PORT=18080 VITE_PORT=5174 make demo-up"
  exit 1
fi

BACKEND_URL="http://localhost:${BACKEND_PORT}"
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"

echo "==> [1/4] Starting Postgres (Compose, host port ${POSTGRES_PORT})…"
$COMPOSE up -d --wait postgres

echo "==> [2/4] Provisioning the demo RLS role (eip_app, NOBYPASSRLS — RLS stays enforced)…"
$COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  < infra/docker-compose/postgres/demo-roles.sql >/dev/null

echo "==> [3/4] Building + starting eip-app (demo profile) on ${BACKEND_PORT}…"
(cd backend && ./gradlew -q :eip-app:bootJar) # gradlew lives under backend/
JAR="$(ls backend/eip-app/build/libs/*.jar | grep -v -- '-plain' | head -1)"
EIP_DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/${POSTGRES_DB}" \
  EIP_APP_DB_USER="eip_app" EIP_APP_DB_PASSWORD="eip_app_dev_pw" \
  EIP_MIGRATOR_DB_USER="$POSTGRES_USER" EIP_MIGRATOR_DB_PASSWORD="$POSTGRES_PASSWORD" \
  SERVER_PORT="$BACKEND_PORT" \
  nohup java -jar "$JAR" --spring.profiles.active=demo >"$DEMO_DIR/backend.log" 2>&1 &
echo $! >"$DEMO_DIR/backend.pid"

printf "    waiting for the API to be healthy"
healthy=0
for _ in $(seq 1 60); do
  if curl -fsS "$BACKEND_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    healthy=1
    echo " — UP"
    break
  fi
  printf "."
  sleep 2
done
if [ "$healthy" -ne 1 ]; then
  echo
  echo "  ✗ backend did not become healthy — see $DEMO_DIR/backend.log"
  exit 1
fi

echo "==> [4/4] Starting the frontend on ${FRONTEND_PORT} (proxy → ${BACKEND_URL}, demo tenant preloaded)…"
pnpm --dir frontend install --frozen-lockfile >/dev/null
VITE_API_PROXY_TARGET="$BACKEND_URL" VITE_EIP_TENANT_ID="$DEMO_TENANT_ID" \
  nohup pnpm --dir frontend exec vite --port "$FRONTEND_PORT" --strictPort \
  >"$DEMO_DIR/frontend.log" 2>&1 &
echo $! >"$DEMO_DIR/frontend.pid"
for _ in $(seq 1 30); do
  curl -fsS "$FRONTEND_URL/" >/dev/null 2>&1 && break
  sleep 1
done

# Record the actual ports so demo-down stops exactly what we started.
cat >"$DEMO_DIR/state" <<EOF
POSTGRES_PORT=$POSTGRES_PORT
BACKEND_PORT=$BACKEND_PORT
FRONTEND_PORT=$FRONTEND_PORT
EOF

cat <<EOF

  ────────────────────────────────────────────────────────────────────────────
   EIP demo is running — seeded SIMULATION data (not real connector ingestion)
     Frontend     $FRONTEND_URL      ← open this
     Backend      $BACKEND_URL
     Demo tenant  $DEMO_TENANT_ID

     curl -H "X-EIP-Tenant: $DEMO_TENANT_ID" $BACKEND_URL/api/v1/session
     curl -H "X-EIP-Tenant: $DEMO_TENANT_ID" $BACKEND_URL/api/v1/connectors
     curl -H "X-EIP-Tenant: $DEMO_TENANT_ID" $BACKEND_URL/api/v1/friction/summary

     Stop with:  make demo-down
  ────────────────────────────────────────────────────────────────────────────

EOF
