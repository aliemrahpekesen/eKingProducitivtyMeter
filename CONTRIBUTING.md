# Contributing to EIP — Developer Onboarding

Goal: from a clean clone to a booted stack and an open PR **in under a day**. This guide reflects the **current repo state** (Phase 0 / v0.1): the backend and frontend build skeletons, the CI pipeline, and the local Docker Compose dev stack exist; substantive module content is built phase by phase per the [development program](program/MasterProgram.md). Every AI engineering session also reads [/CLAUDE.md](CLAUDE.md) first — this guide is its human companion.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Git | any recent | — |
| JDK | a launcher JDK (17–23 recommended) | The build **auto-provisions a Java 21 toolchain** on first run (foojay). If your default `java` is 24/25, that's fine — Gradle's daemon-JVM pin (`backend/gradle/gradle-daemon-jvm.properties`) still runs the build on Java 21. Never run a system Gradle; always use the committed `./gradlew`. |
| Node.js | ≥ 22 | For the frontend. |
| pnpm | 9.15.9 (pinned) | Do **not** `npm i -g pnpm`. Enable the pinned version via Corepack: `corepack enable`. `package.json`'s `packageManager` field pins the exact version. |
| Docker + Compose | Docker 20.10+, Compose v2 | For `make dev-up` (the local infra stack). Start Docker Desktop / the daemon before `make dev-up`. |
| GNU Make | any | Task entry points (`make help`). |

No network beyond the first build (dependency + image pulls) is required to work locally; the platform itself is designed to run air-gapped.

## 2. First run — clone to booted stack

```bash
git clone <repo-url> && cd productivity-meter

# 1) Boot the local infrastructure dev stack (Postgres, Redis, Kafka, MinIO, Keycloak,
#    OTel Collector, Prometheus, Grafana). One command; waits until healthy; prints URLs.
make dev-up

# 2) Build the backend (nine Gradle modules; Java 21 toolchain auto-provisioned first time).
make backend-build          # == cd backend && ./gradlew build

# 3) Build the frontend (Vite + TypeScript; pnpm auto-selected via Corepack).
make frontend-build         # == pnpm --dir frontend install && pnpm --dir frontend build
```

Backend and frontend run **on the host** in dev — only infrastructure runs in Compose. When you're done: `make dev-down` (removes volumes) or `make dev-stop` (keeps data).

## 3. `make` targets

| Target | Does |
|---|---|
| `make help` | List all targets |
| `make dev-up` | Boot the infra dev stack (`core` + `observability` profiles), wait until healthy, print URLs |
| `make dev-down` | Stop the stack and **remove volumes** (clean slate) |
| `make dev-stop` | Stop the stack but **keep volumes** (data persists) |
| `make dev-ps` | Show dev-stack service status |
| `make dev-urls` | Print local endpoints + sample dev credentials |
| `make backend-build` | `cd backend && ./gradlew build` |
| `make frontend-build` | `pnpm --dir frontend install && pnpm --dir frontend build` |

`make dev-up` copies `infra/docker-compose/.env.example` → `infra/docker-compose/.env` on first run (the `.env` is git-ignored). Edit that `.env` only to remap ports (see [§7](#7-troubleshooting)).

## 4. Local service URLs and dev credentials

Printed by `make dev-urls`. These credentials are **non-production dev placeholders** (from `.env.example`) accepted for local use only — never reuse them anywhere real (tracked as DEBT-005 for the future app-container hardening).

| Service | URL / endpoint | Dev credentials |
|---|---|---|
| PostgreSQL 16 + pgvector | `postgresql://eip:eip_dev_pw@localhost:5432/eip` | `eip` / `eip_dev_pw` |
| Redis 7 | `redis://localhost:6379` | — |
| Kafka (KRaft) | `localhost:29092` (in-network `kafka:9092`) | — |
| MinIO (S3) | API `http://localhost:9000` · console `http://localhost:9001` | `eip_minio` / `eip_minio_dev_pw` |
| MinIO buckets | `eip-artifacts`, `eip-ingest` (auto-created) | — |
| Keycloak (OIDC) | `http://localhost:8180/auth` — realm `eip` | admin `admin` / `admin_dev_pw` |
| OTel Collector | OTLP gRPC `localhost:4317` · HTTP `localhost:4318` | — |
| Prometheus | `http://localhost:9090` | — |
| Grafana | `http://localhost:3001` | `admin` / `admin_dev_pw` |

## 5. Build & local validation commands

These are the exact commands CI runs (see [`.github/README.md`](.github/README.md)) — run them locally before pushing so "green locally" means "green in CI".

**Backend** (from `backend/`):

```bash
./gradlew build check        # compile, Spotless, Checkstyle, Error Prone, NullAway,
                             # Modulith/ArchUnit (via check), tests, JaCoCo coverage ratchet
./gradlew spotlessApply      # auto-format Java before committing
```

**Frontend** (from repo root):

```bash
pnpm --dir frontend lint         # ESLint (--max-warnings 0)
pnpm --dir frontend typecheck    # tsc --noEmit (strict)
pnpm --dir frontend build        # tsc + vite build
```

CI (GitHub Actions, `.github/workflows/ci.yml`) runs three jobs on every PR to `main`: **backend** (the `build check` chain), **frontend** (lint/typecheck/build), and **security** (gitleaks + dependency review). *CI runs once the repo is pushed and branch protection is applied — see [`.github/README.md`](.github/README.md); until then, run the commands above locally.*

## 6. Making a change — the contribution flow

The full rules live in the [Engineering Operating System](engineering-operating-system/README.md); the short version:

1. **Read** [/CLAUDE.md](CLAUDE.md) and, if working a planned task, your `/work/tasks/TASK-NNNN.md` and its context pack ([ContextManagementStrategy](engineering-operating-system/ContextManagementStrategy.md)) — read only what the task needs, not the whole repo.
2. **Branch** off `main`: `feature/TASK-NNNN-slug` (or `fix/`, `docs/`, `refactor/`, `adr/`) — [BranchingStrategy](engineering-operating-system/BranchingStrategy.md).
3. **Work** in small commits: `type(scope): summary [TASK-NNNN]` (Conventional Commits — feat/fix/docs/build/refactor/test/chore; scope = module). Run the [§5](#5-build--local-validation-commands) gates locally.
4. **Open a PR** to `main` with the PR template fields. Every PR passes its [quality gates](engineering-operating-system/QualityGatePolicy.md): G1 build/static, G2 tests/coverage, G3 secret/dependency scan, G7 docs, G8 independent review — plus G4/G5/G6 by change class.
5. **Definitions:** a task is claimable only when it meets the [Definition of Ready](engineering-operating-system/DefinitionOfReady.md) (G0); it's done only when it meets the [Definition of Done](engineering-operating-system/DefinitionOfDone.md).

**Never commit** secrets, real tenant data, build outputs, or `.env` (see [RepositoryRules](engineering-operating-system/RepositoryRules.md)); lockfiles (`pnpm-lock.yaml`) and the Gradle wrapper are committed.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `make dev-up` fails: `Bind for 0.0.0.0:5432 failed: port is already allocated` | Another service holds a default port (commonly 5432/3001). Edit `infra/docker-compose/.env` and set the conflicting `*_PORT` to a free value (e.g. `POSTGRES_PORT=15432`, `GRAFANA_PORT=13001`), then `make dev-up` again. |
| `make dev-up` hangs / "unhealthy" | Ensure the Docker daemon is running and has enough memory (~16 GB for the full stack). `make dev-ps` shows per-service status; `docker compose -f infra/docker-compose/docker-compose.yml logs <service>` for detail. |
| Gradle: `Unsupported ... 25.0.1` or a JVM version error | Your default `java` is too new for the Gradle launcher. The committed daemon-JVM pin handles the build JVM; if the launcher still fails, run with a 17–23 JDK: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build` (adjust for your OS). |
| `pnpm` version mismatch under Corepack | Run `corepack enable` (and `corepack prepare pnpm@9.15.9 --activate` once). Do not use a globally-installed pnpm. |
| Backend build wants to download a JDK 21 | Expected on first run — Gradle's toolchain auto-provisions Java 21 (needs network the first time). |
| Frontend `eslint`/`tsc` errors | The gate runs `--max-warnings 0` and `strict` — fix warnings; `no-explicit-any` is an error. |

## 8. Known local limitations (Phase 0)

- **Host port conflicts:** the committed defaults (5432, 3001, …) assume a free host; on a busy machine, override in `.env` (see [§7](#7-troubleshooting)).
- **No application containers yet:** `eip-app`/`eip-workers`/`frontend` run on the host in dev; they join Compose in later phases. Kafka topic creation and Flyway migrations arrive with those.
- **Keycloak** ships a minimal `eip` realm stub (full OIDC config lands in SPRINT-02); the OTel Collector runs without a container healthcheck (its image is distroless).
- **CI is not yet active on the remote:** the pipeline and `main` branch protection activate once the repo is pushed and the ruleset is applied ([`.github/README.md`](.github/README.md)). Run [§5](#5-build--local-validation-commands) locally in the meantime.

## 9. Where to look

| Need | Read |
|---|---|
| Session operating manual | [/CLAUDE.md](CLAUDE.md) |
| How we work (gates, roles, lifecycle) | [engineering-operating-system/README.md](engineering-operating-system/README.md) |
| What to build & in what order | [program/MasterProgram.md](program/MasterProgram.md) · current sprint under `work/sprints/` |
| Repo layout | [engineering-operating-system/RepositoryStructure.md](engineering-operating-system/RepositoryStructure.md) |
| Product spec (source of record) | [docs/](docs/) — `PRD`, architecture, engineering plans |

## Related documents

- [README.md](README.md) — repository index · [/CLAUDE.md](CLAUDE.md) — session bootstrap
- [engineering-operating-system/](engineering-operating-system/README.md) — QualityGatePolicy, BranchingStrategy, DefinitionOfReady/Done, RepositoryRules, DocumentationStandards
- [.github/README.md](.github/README.md) — CI pipeline & branch protection
