# TASK-0015 — R-CR Review (demo hardening: fixed demo tenant + one-command demo)

- **Task:** [../tasks/TASK-0015.md](../tasks/TASK-0015.md) · demo hardening (option A) · **change class CC-7** (dev tooling / demo seed; no contract change)
- **Branch:** `feature/TASK-0015-demo-hardening` · **base:** `integration/SPRINT-00` · **commit reviewed:** `2b52f54`
- **Reviewer:** R-CR (independent) · **Date:** 2026-07-09
- **Method:** independent adversarial review — 3 fresh-context dimensions (security/RLS/scope, build+demo-path, docs/honesty/records), every finding adversarially re-verified. 8 agents; 1 finding refuted. The build dimension independently ran `./gradlew check` + all frontend gates. Cited: DemoDataSeeder, application.yaml, R__rls_policies.sql, BaselineRlsIntegrationTest, the demo scripts + Makefile + `.env` template.

## Overall verdict: **APPROVED** — 0 BLOCKER · 0 MAJOR · 1 MINOR · 3 NIT

**15/15 checks PASS** (0 FAIL, 0 CONCERN). The security-critical properties hold: the app runtime connects as **`eip_app` (NOBYPASSRLS)** so **RLS stays fully enforced** (running Flyway as the superuser is a migration-time-only dev shortcut and does not touch runtime isolation); the fixed demo tenant is `@Profile("demo")` and never referenced by production code; no real secret is committed; and scope is limited to demo tooling (no new API endpoints, connectors, AI, or auth). The one MINOR was remediated.

## Verification (independently confirmed)

| Area | Verdict | Evidence |
|---|---|---|
| **RLS enforced** | **PASS** | app datasource = `eip_app` (application.yaml), created **NOBYPASSRLS** (demo-roles.sql); superuser used only for Flyway/DDL; end-to-end proof: a different tenant → 0 rows, no tenant → 401 |
| **App not superuser** | **PASS** | `spring.datasource` never points at a BYPASSRLS/superuser role |
| **Demo-only tenant** | **PASS** | `DEMO_TENANT_ID` appears only in `DemoDataSeeder` (`@Profile("demo")`); frontend reads it from `VITE_EIP_TENANT_ID` with a `''` fallback — a prod build has no hard-coded tenant |
| **No secrets** | **PASS** | only credential is the dev-only `eip_app_dev_pw` in a dev-only init script (DEBT-005 convention); no tokens/keys; `.demo/` git-ignored |
| **Scope clean** | **PASS** | 10 files, demo tooling only; the sole Java change swaps random→fixed UUID + 2 log lines; **no new endpoints** |
| **`./gradlew check`** | **PASS** | BUILD SUCCESSFUL |
| **Frontend gates** | **PASS** | install-frozen/format/lint/typecheck/test(13)/build all green |
| **demo-up correctness** | **PASS** | sound ordering (Postgres → role → boot → frontend), idempotent (role DO-guard + seeder slug-check), health-gated, executable-jar glob correct |
| **demo-down** | **PASS** | SIGTERMs the backend pid + `compose stop postgres`; keeps the volume |
| **frontend tenant wiring** | **PASS** | `VITE_EIP_TENANT_ID` = the fixed tenant is exported to `pnpm dev`; UI loads with no DB lookup |
| **Honesty held** | **PASS** | connectors stay `simulation=true` (SIMULATION badges), demo banner + team-level-only preserved; no "real ingestion" claim; no `frontend/src` change |
| **Runbook + records** | **PASS** | README + MODULE.md `make demo-up`/`demo-down` match the scripts; task file + DEBT refs (005/016) accurate; generated client (DEBT-016) correctly NOT started |

## Findings

### MINOR (remediated)

- **MINOR-1 — `demo-up.sh` read `POSTGRES_*` from the shell, not from the `.env` it passes to Compose.** A customized `infra/docker-compose/.env` (e.g. a non-default `POSTGRES_PORT`/password) would leave Compose using the `.env` values while the host-run backend used the script's shell defaults → the backend could connect to the wrong port/DB. *Location:* [scripts/demo/demo-up.sh](../../scripts/demo/demo-up.sh). → **Fixed**: the script now sources the same `.env` (`set -a; . "$ENV_FILE"; set +a`) before reading `POSTGRES_*`, so the backend connection always matches the DB Compose started (verified: sourcing loads `POSTGRES_USER/PORT/DB`).

### NIT (acknowledged, no change)

- **NIT-1 — OTLP trace export targets `localhost:4318` but `make demo-up` starts only the `core` Compose profile** (no `otel-collector`). Harmless — the exporter is async and **fail-open** (the app runs fine; traceId still flows through logs). A developer wanting the full observability stack runs `make dev-up` instead. Left as-is to keep the demo lightweight (Postgres-only).
- **NIT-2 — `eip_app_dev_pw` tracked under DEBT-005** rather than a separate entry. Accurate: it's a dev-only credential in the same class as the existing `.env.example` dev creds; DEBT-005 already owns the `CHANGE_ME`/secrets convergence for the app-container work.

### Refuted

- **NIT → REFUTED — committed dev credential broadens DEBT-005.** Refuted: `eip_app_dev_pw` is dev-only, disclosed, and consistent with the established convention; it does not expand the risk surface (production never uses it; the header states so).

## Gate status (CC-7)

| Gate | Status |
|---|---|
| G1/G2 Build & Tests | **PASS** — `./gradlew check` + frontend gates green |
| G3 Security | **PASS** — RLS enforced (NOBYPASSRLS app role); no secrets; scope clean |
| G7 Documentation | **PASS** — README + MODULE.md runbook; task + DEBT refs accurate |
| G8 Review & Done | this review |
| G4 | n/a — no contract anchor touched |

## Can it merge?

**Yes — cleared to merge into `integration/SPRINT-00`** (0 BLOCKER, 0 MAJOR; MINOR remediated; all gates green; end-to-end demo verified with RLS intact). **Not merged, not pushed** per instruction. The demo-UX gap from the TASK-0014 review is resolved: `make demo-up` → open `http://localhost:5173`, no manual DB tenant lookup.
