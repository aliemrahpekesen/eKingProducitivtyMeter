#!/usr/bin/env bash
# EIP one-command local install (macOS/Linux). Stands up the FULL infra stack (Postgres, Redis,
# Kafka, MinIO, Keycloak, OTel Collector, Prometheus, Grafana), provisions the RLS role, builds and
# starts the backend with the selected environment profile, starts the frontend in the matching
# Vite mode, smoke-tests every live endpoint, and prints endpoints + sample-user info.
#
#   ./scripts/install/install.sh [--env dev|test|preprod|prod] [--core-only]
#
# Environments (ADR-022): config/environments/<env>.env — dev/test seed the demo dataset,
# preprod does not, prod refuses to boot until OIDC lands (DEBT-012). Every port is overridable:
# shell env > config/environments/<env>.env > infra/docker-compose/.env > compose defaults.
# Stop with scripts/install/stop.sh · wipe with scripts/install/uninstall.sh (destructive).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

EIP_ENV="dev"
CORE_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --env) EIP_ENV="$2"; shift 2 ;;
    --env=*) EIP_ENV="${1#--env=}"; shift ;;
    --core-only) CORE_ONLY=1; shift ;;  # skip the observability stack (faster, low-RAM machines)
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -12; exit 0 ;;
    *) echo "unknown argument: $1 (see --help)"; exit 2 ;;
  esac
done

ENV_CONF="config/environments/${EIP_ENV}.env"
[ -f "$ENV_CONF" ] || { echo "✗ unknown environment '${EIP_ENV}' — expected one of: dev test preprod prod"; exit 2; }

if [ "$EIP_ENV" = "prod" ]; then
  cat <<'MSG'
  ✗ prod is configuration-complete but INTENTIONALLY not bootable in this release.
    OIDC tenant resolution is not implemented yet (DEBT-012); ProductionTenantResolutionGuard
    refuses startup so the dev header tenant resolver can never serve production traffic.
    Use --env preprod for a production rehearsal, or dev/test for seeded environments.
MSG
  exit 2
fi

STATE_DIR=".install"
COMPOSE_ENV="infra/docker-compose/.env"
mkdir -p "$STATE_DIR"
[ -f "$COMPOSE_ENV" ] || cp infra/docker-compose/.env.example "$COMPOSE_ENV"

# --- config resolution: shell env > env file > compose .env > default --------------------------
envget() { # key default
  local key="$1" def="$2" val
  val="$(printenv "$key" 2>/dev/null || true)"
  if [ -z "${val:-}" ]; then val="$(grep -E "^${key}=" "$ENV_CONF" 2>/dev/null | tail -1 | cut -d= -f2- | cut -d'#' -f1 | tr -d ' ' || true)"; fi
  if [ -z "${val:-}" ]; then val="$(grep -E "^${key}=" "$COMPOSE_ENV" 2>/dev/null | tail -1 | cut -d= -f2- || true)"; fi
  printf '%s' "${val:-$def}"
}

SPRING_PROFILE="$(envget SPRING_PROFILES_ACTIVE dev)"
VITE_MODE="$(envget VITE_MODE development)"
FRONTEND_SERVE="$(envget FRONTEND_SERVE dev)"
SEEDED="$(envget SEEDED 1)"
BACKEND_PORT="$(envget EIP_BACKEND_PORT 8080)"
FRONTEND_PORT="$(envget VITE_PORT 5173)"
POSTGRES_PORT="$(envget POSTGRES_PORT 5432)"
POSTGRES_USER="$(envget POSTGRES_USER eip)"
POSTGRES_PASSWORD="$(envget POSTGRES_PASSWORD eip_dev_pw)"
POSTGRES_DB="$(envget POSTGRES_DB eip)"
REDIS_PORT="$(envget REDIS_PORT 6379)"
KAFKA_PORT="$(envget KAFKA_PORT 29092)"
MINIO_API_PORT="$(envget MINIO_API_PORT 9000)"
MINIO_CONSOLE_PORT="$(envget MINIO_CONSOLE_PORT 9001)"
KEYCLOAK_PORT="$(envget KEYCLOAK_PORT 8180)"
KEYCLOAK_MGMT_PORT="$(envget KEYCLOAK_MGMT_PORT 9010)"
OTLP_GRPC_PORT="$(envget OTLP_GRPC_PORT 4317)"
OTLP_HTTP_PORT="$(envget OTLP_HTTP_PORT 4318)"
PROMETHEUS_PORT="$(envget PROMETHEUS_PORT 9090)"
GRAFANA_PORT="$(envget GRAFANA_PORT 3001)"
DEMO_TENANT_ID="00000000-0000-4000-8000-0000000000de" # DemoDataProperties.DEFAULT_TENANT_ID
export POSTGRES_PORT POSTGRES_USER POSTGRES_PASSWORD POSTGRES_DB REDIS_PORT KAFKA_PORT \
  MINIO_API_PORT MINIO_CONSOLE_PORT KEYCLOAK_PORT KEYCLOAK_MGMT_PORT OTLP_GRPC_PORT \
  OTLP_HTTP_PORT PROMETHEUS_PORT GRAFANA_PORT

COMPOSE="docker compose -f infra/docker-compose/docker-compose.yml --env-file $COMPOSE_ENV --profile core"
INFRA_SERVICES="postgres redis kafka minio keycloak"
if [ "$CORE_ONLY" -ne 1 ]; then
  COMPOSE="$COMPOSE --profile observability"
  INFRA_SERVICES="$INFRA_SERVICES otel-collector prometheus grafana"
fi

echo "==> EIP install — environment: ${EIP_ENV} (Spring profile: ${SPRING_PROFILE}, Vite mode: ${VITE_MODE})"

# --- [1/8] prerequisites ------------------------------------------------------------------------
echo "==> [1/8] Checking prerequisites…"
fail=0
need() { command -v "$1" >/dev/null 2>&1 || { echo "  ✗ missing: $1 — $2"; fail=1; }; }
need docker "install Docker Desktop / Engine (https://docs.docker.com/get-docker/)"
need java   "install a Java 21 JDK (e.g. 'brew install temurin@21' / 'sdk install java 21-tem')"
need node   "install Node.js >= 20 (https://nodejs.org)"
need pnpm   "install pnpm ('corepack enable' or 'npm i -g pnpm')"
need curl   "install curl"
if command -v docker >/dev/null 2>&1; then
  docker info >/dev/null 2>&1 || { echo "  ✗ Docker daemon is not running — start Docker first"; fail=1; }
  docker compose version >/dev/null 2>&1 || { echo "  ✗ Docker Compose v2 plugin missing"; fail=1; }
fi
if command -v java >/dev/null 2>&1; then
  JMAJOR="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
  [ "${JMAJOR:-0}" -ge 21 ] || { echo "  ✗ Java ${JMAJOR:-?} found — Java 21+ required"; fail=1; }
fi
if command -v node >/dev/null 2>&1; then
  NMAJOR="$(node -v | sed -E 's/^v([0-9]+).*/\1/')"
  [ "${NMAJOR:-0}" -ge 20 ] || { echo "  ✗ Node ${NMAJOR:-?} found — Node 20+ required"; fail=1; }
fi
[ "$fail" -eq 0 ] || { echo "  Fix the prerequisites above and re-run."; exit 1; }
echo "    ✓ docker + compose v2, java 21+, node 20+, pnpm, curl"

# --- [2/8] port conflicts (fail fast, print exact overrides) ------------------------------------
echo "==> [2/8] Checking ports…"
port_holder() { lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print $1" (pid "$2")"; exit}'; }
conflict=0
check_port() { # port label overrideVar
  local who; who="$(port_holder "$1" || true)"
  if [ -n "$who" ]; then echo "  ✗ $2 port $1 in use by: $who   → override:  $3=<free-port> ./scripts/install/install.sh --env ${EIP_ENV}"; conflict=1; fi
}
check_port "$POSTGRES_PORT"      "Postgres"        POSTGRES_PORT
check_port "$REDIS_PORT"         "Redis"           REDIS_PORT
check_port "$KAFKA_PORT"         "Kafka"           KAFKA_PORT
check_port "$MINIO_API_PORT"     "MinIO API"       MINIO_API_PORT
check_port "$MINIO_CONSOLE_PORT" "MinIO console"   MINIO_CONSOLE_PORT
check_port "$KEYCLOAK_PORT"      "Keycloak"        KEYCLOAK_PORT
check_port "$KEYCLOAK_MGMT_PORT" "Keycloak mgmt"   KEYCLOAK_MGMT_PORT
check_port "$BACKEND_PORT"       "Backend"         EIP_BACKEND_PORT
check_port "$FRONTEND_PORT"      "Frontend"        VITE_PORT
if [ "$CORE_ONLY" -ne 1 ]; then
  check_port "$OTLP_GRPC_PORT"   "OTel OTLP gRPC"  OTLP_GRPC_PORT
  check_port "$OTLP_HTTP_PORT"   "OTel OTLP HTTP"  OTLP_HTTP_PORT
  check_port "$PROMETHEUS_PORT"  "Prometheus"      PROMETHEUS_PORT
  check_port "$GRAFANA_PORT"     "Grafana"         GRAFANA_PORT
fi
[ "$conflict" -eq 0 ] || { echo "  Refusing to start — free the port(s) above or re-run with the printed overrides."; exit 1; }
echo "    ✓ all ports free"

# --- [3/8] infra --------------------------------------------------------------------------------
echo "==> [3/8] Starting infra ($INFRA_SERVICES)…"
# shellcheck disable=SC2086
$COMPOSE up -d --wait --wait-timeout 900 $INFRA_SERVICES
$COMPOSE run --rm minio-init >/dev/null
echo "    ✓ infra healthy (incl. MinIO buckets)"

# --- [4/8] database role ------------------------------------------------------------------------
echo "==> [4/8] Provisioning the RLS role (eip_app, NOBYPASSRLS — RLS enforced end-to-end)…"
$COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  < infra/docker-compose/postgres/demo-roles.sql >/dev/null

# --- [5/8] backend ------------------------------------------------------------------------------
echo "==> [5/8] Building + starting eip-app (profile: ${SPRING_PROFILE}) on :${BACKEND_PORT}…"
(cd backend && ./gradlew -q :eip-app:bootJar)
JAR="$(ls backend/eip-app/build/libs/*.jar | grep -v -- '-plain' | head -1)"
EIP_DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/${POSTGRES_DB}" \
  EIP_APP_DB_USER="eip_app" EIP_APP_DB_PASSWORD="eip_app_dev_pw" \
  EIP_MIGRATOR_DB_USER="$POSTGRES_USER" EIP_MIGRATOR_DB_PASSWORD="$POSTGRES_PASSWORD" \
  EIP_OTLP_TRACES_ENDPOINT="http://localhost:${OTLP_HTTP_PORT}/v1/traces" \
  SERVER_PORT="$BACKEND_PORT" SPRING_PROFILES_ACTIVE="$SPRING_PROFILE" \
  nohup java -jar "$JAR" >"$STATE_DIR/backend.log" 2>&1 &
echo $! >"$STATE_DIR/backend.pid"
BACKEND_URL="http://localhost:${BACKEND_PORT}"
printf "    waiting for the API"
healthy=0
for _ in $(seq 1 90); do
  if curl -fsS "$BACKEND_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then healthy=1; echo " — UP"; break; fi
  printf "."; sleep 2
done
[ "$healthy" -eq 1 ] || { echo; echo "  ✗ backend not healthy — see $STATE_DIR/backend.log"; exit 1; }

# --- [6/8] frontend -----------------------------------------------------------------------------
echo "==> [6/8] Starting the frontend (mode: ${VITE_MODE}, serve: ${FRONTEND_SERVE}) on :${FRONTEND_PORT}…"
pnpm --dir frontend install --frozen-lockfile >/dev/null
if [ "$FRONTEND_SERVE" = "preview" ]; then
  (cd frontend && pnpm exec vite build --mode "$VITE_MODE" >/dev/null)
  VITE_API_PROXY_TARGET="$BACKEND_URL" \
    nohup pnpm --dir frontend exec vite preview --port "$FRONTEND_PORT" --strictPort \
    >"$STATE_DIR/frontend.log" 2>&1 &
else
  VITE_API_PROXY_TARGET="$BACKEND_URL" \
    nohup pnpm --dir frontend exec vite --mode "$VITE_MODE" --port "$FRONTEND_PORT" --strictPort \
    >"$STATE_DIR/frontend.log" 2>&1 &
fi
echo $! >"$STATE_DIR/frontend.pid"
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"
for _ in $(seq 1 30); do curl -fsS "$FRONTEND_URL/" >/dev/null 2>&1 && break; sleep 1; done

# --- [7/8] smoke: every live function -----------------------------------------------------------
echo "==> [7/8] Smoke-testing the live surface…"
smoke_fail=0
expect() { # description command...
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "    ✓ $desc"; else echo "    ✗ $desc"; smoke_fail=1; fi
}
expect "actuator health UP"           sh -c "curl -fsS $BACKEND_URL/actuator/health | grep -q '\"status\":\"UP\"'"
expect "actuator prometheus metrics"  sh -c "curl -fsS $BACKEND_URL/actuator/prometheus | grep -q eip_api"
expect "OpenAPI contract served"      sh -c "curl -fsS $BACKEND_URL/v3/api-docs | grep -q '/api/v1/friction/summary'"
expect "missing tenant fails closed (401)" sh -c "curl -s -o /dev/null -w '%{http_code}' $BACKEND_URL/api/v1/session | grep -q 401"
if [ "$SEEDED" = "1" ]; then
  T="$DEMO_TENANT_ID"
  expect "session (tenant identity)"  sh -c "curl -fsS -H 'X-EIP-Tenant: $T' $BACKEND_URL/api/v1/session | grep -q '$T'"
  expect "connectors list (paged)"    sh -c "curl -fsS -H 'X-EIP-Tenant: $T' '$BACKEND_URL/api/v1/connectors?limit=2' | grep -q '\"hasMore\"'"
  expect "computed friction summary"  sh -c "curl -fsS -H 'X-EIP-Tenant: $T' $BACKEND_URL/api/v1/friction/summary | grep -q 'engineering_friction_v0.1'"
  TEAM_ID="$(curl -fsS -H "X-EIP-Tenant: $T" "$BACKEND_URL/api/v1/friction/summary" | sed -nE 's/.*"teamId":"([0-9a-f-]{36})".*/\1/p' | head -1)"
  expect "evidence drill-down"        sh -c "curl -fsS -H 'X-EIP-Tenant: $T' $BACKEND_URL/api/v1/friction/teams/$TEAM_ID/evidence | grep -q '\"transitions\"'"
  expect "frontend UI served"         sh -c "curl -fsS $FRONTEND_URL/ | grep -qi '<div id=\"root\"'"
else
  expect "frontend UI served"         sh -c "curl -fsS $FRONTEND_URL/ | grep -qi '<div id=\"root\"'"
  echo "    (no seed in ${EIP_ENV} — data endpoints answer per-tenant once you load data)"
fi
[ "$smoke_fail" -eq 0 ] || { echo "  ✗ smoke test failed — logs: $STATE_DIR/backend.log, $STATE_DIR/frontend.log"; exit 1; }

# --- [8/8] state + summary ----------------------------------------------------------------------
cat >"$STATE_DIR/state" <<EOF
EIP_ENV=$EIP_ENV
CORE_ONLY=$CORE_ONLY
BACKEND_PORT=$BACKEND_PORT
FRONTEND_PORT=$FRONTEND_PORT
POSTGRES_PORT=$POSTGRES_PORT
EOF
GRAF=""; PROM=""; OTELL=""
if [ "$CORE_ONLY" -ne 1 ]; then
  GRAF="     Grafana      http://localhost:${GRAFANA_PORT}   (admin / admin_dev_pw)"
  PROM="     Prometheus   http://localhost:${PROMETHEUS_PORT}"
  OTELL="     OTel OTLP    grpc :${OTLP_GRPC_PORT} · http :${OTLP_HTTP_PORT}"
fi
cat <<EOF

  ══════════════════════════════════════════════════════════════════════════════
   EIP is running — environment: ${EIP_ENV} (SIMULATION data source; no real connectors yet)

     Frontend     ${FRONTEND_URL}      ← open this
     Backend API  ${BACKEND_URL}/api/v1        OpenAPI: ${BACKEND_URL}/v3/api-docs
     Health       ${BACKEND_URL}/actuator/health
$( [ "$SEEDED" = "1" ] && cat <<SEEDEOF
     Demo tenant  ${DEMO_TENANT_ID}
                  (preloaded in the UI; teams Platform 91 > Payments 56 > Web 50)
       curl -H "X-EIP-Tenant: ${DEMO_TENANT_ID}" ${BACKEND_URL}/api/v1/friction/summary
SEEDEOF
)
     PostgreSQL   postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@localhost:${POSTGRES_PORT}/${POSTGRES_DB}   (app role: eip_app, RLS enforced)
     Redis        redis://localhost:${REDIS_PORT}
     Kafka        localhost:${KAFKA_PORT}
     MinIO        http://localhost:${MINIO_CONSOLE_PORT}   (eip_minio / eip_minio_dev_pw)
     Keycloak     http://localhost:${KEYCLOAK_PORT}   (admin / admin_dev_pw)
${OTELL}
${PROM}
${GRAF}

     Stop:        ./scripts/install/stop.sh        (keeps data)
     Uninstall:   ./scripts/install/uninstall.sh   (DESTRUCTIVE: removes volumes)
  ══════════════════════════════════════════════════════════════════════════════

EOF
