# Local Install — one command, all environments

The install tooling stands the platform up end-to-end on a developer machine: the **full infra
stack** (PostgreSQL 16+pgvector, Redis, Kafka KRaft, MinIO + buckets, Keycloak, OTel Collector,
Prometheus, Grafana), the RLS-enforced backend (`eip-app` as the NOBYPASSRLS `eip_app` role), and
the frontend — then smoke-tests every live endpoint and prints the access summary.

| OS | Command |
|---|---|
| macOS / Linux | `./scripts/install/install.sh --env dev` (or `make install ENV=dev`) |
| Windows (PowerShell 5.1+/7) | `.\scripts\install\install.ps1 -Env dev` |

Stop (keeps data): `./scripts/install/stop.sh` · `.\scripts\install\stop.ps1` — Uninstall
(**destructive**, removes volumes): `./scripts/install/uninstall.sh` · `.\scripts\install\uninstall.ps1`.
Add `--core-only` / `-CoreOnly` to skip the observability stack on low-RAM machines.

## Prerequisites

Docker (daemon running, Compose v2), Java 21+, Node 20+, pnpm, curl. The installer verifies all of
them and fails fast with install hints. All ports are checked up front; a conflict prints the exact
override, e.g. `POSTGRES_PORT=55433 EIP_BACKEND_PORT=18080 VITE_PORT=5174 ./scripts/install/install.sh --env dev`.

## Environments (ADR-022)

One Spring config file per environment (`backend/eip-app/src/main/resources/application-<env>.yaml`),
one installer config per environment (`config/environments/<env>.env`), one Vite mode file per
environment (`frontend/.env.<mode>` — public values only, never secrets):

| Env | Spring profile | Seeds demo data | Logs | Frontend | Boots? |
|---|---|---|---|---|---|
| `dev` | `dev` (group → `demo`) | yes | human-readable | vite dev server (hot reload) | yes |
| `test` | `test` (group → `demo`) | yes | structured JSON | vite dev server | yes |
| `preprod` | `preprod` | no | structured JSON | production build + `vite preview` | yes |
| `prod` | `prod` | no | structured JSON | production build | **refused** until OIDC lands ([DEBT-012](../../work/debt-register.md)) — `ProductionTenantResolutionGuard` fails startup so header/demo tenant resolution can never serve production traffic; the installer explains and exits |

Config precedence everywhere: shell env → `config/environments/<env>.env` → `infra/docker-compose/.env`
→ compose defaults.

## What "running" means (smoke-tested on every install)

Actuator health/info/prometheus; OpenAPI at `/v3/api-docs`; missing tenant fails closed (401
problem+json); and for seeded environments (dev/test) with the fixed demo tenant
`00000000-0000-4000-8000-0000000000de`: `/api/v1/session`, `/api/v1/connectors` (cursor-paged),
`/api/v1/friction/summary` (computed Engineering Friction v0.1 — Platform 91 > Payments 56 >
Web 50), `/api/v1/friction/teams/{teamId}/evidence` (drill-down), and the frontend UI with the
friction card + evidence drawer. Data is **SIMULATION** (deterministic dataset; no real connectors
yet — DEBT-017/018).

## Access summary printed after install (defaults)

Frontend `:5173` · Backend `:8080` (`/api/v1`, OpenAPI, actuator) · PostgreSQL `:5432`
(`eip`/`eip_dev_pw`, app role `eip_app` RLS-enforced) · Redis `:6379` · Kafka `:29092` · MinIO
console `:9001` (`eip_minio`/`eip_minio_dev_pw`) · Keycloak `:8180` (`admin`/`admin_dev_pw`) ·
OTLP `:4317`/`:4318` · Prometheus `:9090` · Grafana `:3001` (`admin`/`admin_dev_pw`). All are dev
fixtures (never production values — [DEBT-005](../../work/debt-register.md) governs the prod
`CHANGE_ME` convention).

## Relationship to the older runners

`make demo-up`/`demo-down` (minimal Postgres-only demo) and `make dev-up`/`dev-down` (infra only)
remain for quick loops; the installer is the complete, environment-aware path and the one this
runbook recommends.
