#!/usr/bin/env bash
# DESTRUCTIVE teardown: stops the apps, removes all EIP containers AND data volumes. Asks for
# confirmation unless --yes is passed.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"

if [ "${1:-}" != "--yes" ]; then
  printf "This DELETES all EIP containers and data volumes (Postgres data included). Type 'yes' to continue: "
  read -r answer
  [ "$answer" = "yes" ] || { echo "aborted"; exit 1; }
fi

bash scripts/install/stop.sh || true
COMPOSE_ENV="infra/docker-compose/.env"
[ -f "$COMPOSE_ENV" ] || cp infra/docker-compose/.env.example "$COMPOSE_ENV"
docker compose -f infra/docker-compose/docker-compose.yml --env-file "$COMPOSE_ENV" \
  --profile core --profile observability down -v --remove-orphans
rm -rf .install
echo "EIP uninstalled (containers + volumes removed)"
