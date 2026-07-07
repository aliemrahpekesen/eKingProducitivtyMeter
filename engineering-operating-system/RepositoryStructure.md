# Repository Structure

This document is the canonical map of the EIP monorepo: the full target tree, the phase in which each path appears, and the owning engineering role per top-level path. It merges the planned layout from [../README.md](../README.md) with the EOS additions (`/work`, `/docs/adr`, `CLAUDE.md`, `CODEOWNERS`, per-module `MODULE.md`). Engineering agents use it to locate anything without searching; R-TPM uses it to validate task write-sets. Path ownership detail and CODEOWNERS content live in [./ModuleOwnership.md](./ModuleOwnership.md).

## 1. Consolidated target tree

```
/
├── CLAUDE.md                        # session bootstrap read first by every engineering agent
├── CODEOWNERS                       # paths → owning roles (ModuleOwnership.md §4)
├── README.md                        # repository index and documentation map
├── Makefile                         # dev workflow entry points (make dev-up, make test, …)
├── .github/workflows/               # CI pipelines implementing G1–G3/G7 + release pipeline
├── docs/                            # documentation of record (unchanged by the EOS)
│   ├── vision/ product/ architecture/ ai/ engineering/
│   ├── infrastructure/ testing/ operations/ implementation/
│   ├── architecture/generated/      # committed Modulith documentation snapshots
│   └── adr/                         # ADR-NNN-slug.md (Phase 0 backfills ADR-001..014)
├── engineering-operating-system/    # 33 EOS docs + README index (this bundle)
├── work/                            # L3 work state: sprints/ tasks/ handoffs/ + registers
├── backend/                         # Gradle multi-module modular monolith (§2)
│   ├── settings.gradle.kts  gradle/  gradlew  buildSrc/
│   └── eip-app/ eip-core/ eip-tenancy/ eip-connectors/ eip-ingestion/
│       eip-analytics/ eip-ai/ eip-reports/ eip-workers/     # each with MODULE.md
├── frontend/                        # React 18 + TS + Vite SPA (§3), MODULE.md, pnpm
├── infra/                           # docker-compose/ k8s/ grafana/ (§4)
├── simulation/                      # packs/demo-small|demo-midsize|demo-troubled|enterprise-large
└── scripts/                         # developer/operator tooling, idea/ code style
```

## 1.1 Top-level layout, phase, and ownership

| Path | Purpose (one line) | Appears | Owner |
|---|---|---|---|
| `/CLAUDE.md` | Universal session bootstrap — the operating manual every engineering agent reads first | Phase 0 | R-CA |
| `/CODEOWNERS` | Maps paths to owning engineering roles (content: [./ModuleOwnership.md](./ModuleOwnership.md) §4) | Phase 0 | R-CA |
| `/README.md` | Repository index and documentation map | exists | R-DE |
| `/Makefile` | Single entry point for dev workflows (`make dev-up`, `make test`, … per [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §13) | Phase 0 | R-DOA |
| `/.github/` | CI workflows implementing gates G1–G3/G7 and the release pipeline | Phase 0 | R-DOA |
| `/docs/` | Product specification workspace — the documentation of record (unchanged by the EOS) | exists | R-DE |
| `/docs/adr/` | `ADR-NNN-slug.md` decision records; Phase 0 backfills ADR-001..014 from [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §7 | Phase 0 | R-CA |
| `/docs/architecture/generated/` | Committed Spring Modulith documentation snapshots ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §3) | Phase 0 | R-BA |
| `/engineering-operating-system/` | The 33 EOS documents + README index (this bundle) | Phase 0 | R-CA |
| `/work/` | Shared work state: sprints, tasks, handoffs, debt/risk registers (L3 memory layer) | Phase 0 | R-TPM |
| `/backend/` | Java 21 / Spring Boot 3.x Gradle multi-module modular monolith (§2) | Phase 0 | R-BA |
| `/frontend/` | React 18 + TypeScript + Vite SPA ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md)) | Phase 0 | R-FA |
| `/infra/` | Docker Compose, Kubernetes/OpenShift manifests, Grafana dashboards | Phase 0 | R-DOA |
| `/simulation/` | Simulated enterprise data packs for demo, development, and testing | Phase 1 | R-CNA |
| `/scripts/` | Developer and operator tooling (IDE config, generators, ops helpers) | Phase 0 | R-DOA |

## 2. Backend: Gradle multi-module mapping

The `/backend` Gradle build contains exactly the nine modules of [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1 — that table (key packages, allowed dependencies) is authoritative; the Modulith verification tests enforce it in CI (G1). Every module root carries a `MODULE.md` charter (purpose, owned tables/topics/endpoints, invariants, dependencies) created with the module in Phase 0/1 and maintained by the owning role ([./ModuleOwnership.md](./ModuleOwnership.md) §3).

| Gradle module | Role in the build | Root package | Owner (co-owner) |
|---|---|---|---|
| `eip-app` | Composition root: REST controllers, OpenAPI, security filter chain, problem+json advice — no business logic | `com.eip.app` | R-BA |
| `eip-core` | Shared kernel (leaf): canonical entities, event envelope, UUIDv7 IDs, secrets/KMS SPI, storage + VectorStore SPIs, error taxonomy | `com.eip.core` | R-BA |
| `eip-tenancy` | Organizations/teams/members, RBAC, audit log, tenant context + RLS session variable management | `com.eip.tenancy` | R-BA (R-PA: tenancy/quotas) |
| `eip-connectors` | Connector SPI + all built-in connectors incl. simulation mode | `com.eip.connectors` | R-CNA |
| `eip-ingestion` | Sync engine, checkpointing, raw staging, normalizers, dedup, DLQ handling | `com.eip.ingestion` | R-CNA (R-DA: normalization) |
| `eip-analytics` | Metric engines, metric definitions registry, risk scoring, forecasts, JdbcClient query side | `com.eip.analytics` | R-DA (R-BA: runtime) |
| `eip-ai` | Agent orchestration (LangChain4j), LLM provider SPI, RAG pipeline, MCP client/server, LLM audit | `com.eip.ai` | R-AIA |
| `eip-reports` | Report/template engine, versioned artifact library, exports, scheduled generation | `com.eip.reports` | R-BA (R-AIA: composition) |
| `eip-workers` | Second composition root: Kafka consumers, Quartz, agent executors — no REST API | `com.eip.workers` | R-BA |

Build scaffolding inside `/backend`:

```
/backend
  settings.gradle.kts            # declares the nine modules above — nothing else
  gradle/libs.versions.toml      # version catalog: single source for all backend versions
  gradle/verification-metadata.xml  # dependency checksum verification (./DependencyManagement.md §10)
  gradlew, gradlew.bat, gradle/wrapper/   # committed wrapper — system Gradle is never used
  buildSrc/                      # convention plugins: eip.java-conventions, eip.boot-app-conventions,
                                 #   eip.modulith-conventions, eip.dependency-rules
  eip-app/
    MODULE.md
    src/main/resources/db/migration/   # ALL Flyway migrations (V<seq>__*.sql, R__*.sql) — owner R-DBA
  eip-core/ … eip-workers/       # each: MODULE.md + src/{main,test} per BackendPlan §1 packages
```

Cross-module dependency edges not listed in [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1 MUST NOT be added; a `ModularityTests` failure is a compile-red event, not a review comment.

### 2.1 When module content arrives

All nine module skeletons (build files, `MODULE.md`, package roots, Modulith verification harness) exist from Phase 0; substantive content lands per the phase mappings in [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §17 and [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §13:

| Phase (version) | Backend content | Frontend content |
|---|---|---|
| 0 (v0.1) | `eip-core` kernel + secrets SPI, `eip-tenancy` (RBAC/audit/RLS context), `eip-app` (OpenAPI, security chain, error taxonomy), CI stages 1–7 | Shell + auth + tenant switcher, design tokens/Mantine theme, generated-client pipeline, `EipDataTable` |
| 1 (v0.2) | `eip-connectors` (SPI + Jira/GitHub/simulation), `eip-ingestion` (sync engine, outbox, DLQ) | Integration management + wizard (SchemaForm), connector/job/queue monitors |
| 2 (v0.3) | `eip-analytics` (metric engine, JdbcClient query side), Quartz rollups | Widget grid + saved views, productivity/sprint/kanban/quality dashboards |
| 3 (v0.4) | `eip-ai` (LLM SPI, RAG, first agents), `eip-reports` (engine + artifact library) | LLM provider config, agent management with SSE run view, RAG KB, report center |
| 4 (v0.5) | remaining agents, MCP client/server, schedules/notifications | MCP config, remaining dashboards, scheduling UIs, pptx exports |
| 5 (v1.0) | HA workers, performance passes, remaining connectors, upgrade paths | performance/a11y/i18n hardening, browser-matrix sign-off |

## 3. Frontend layout

Authoritative structure: [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §12. Summary with EOS additions:

```
/frontend
  MODULE.md                # frontend charter (owner R-FA)
  package.json             # packageManager pins pnpm 9+; pnpm-lock.yaml committed
  /src
    /api/generated         # CI-generated OpenAPI TS client — committed, never hand-edited
    /api                   # client.ts fetch wrapper, sse.ts typed SSE helper
    /app                   # shell, router, providers, auth context, permission gates
    /design                # tokens.css, Mantine theme, ECharts theme, EipChart, EipDataTable
    /features              # admin, connectors, tenancy, dashboards, work, ai, reports, audit
    /forms                 # SchemaForm renderer + field registry
    /i18n                  # locale resources per feature
    /test                  # MSW handlers, fixtures, test utils
  /e2e                     # Playwright specs + persona fixtures
```

Cross-feature imports only via `/design`, `/api`, `/app`, `/forms` (enforced by lint per [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §12).

## 4. Infra, simulation, scripts, work

```
/infra
  /docker-compose          # compose.yaml (core/observability/ai-local/simulation profiles),
                           #   .env.example, keycloak/eip-realm.json    — Phase 0
  /k8s                     # Kustomize bases + overlays per ../docs/infrastructure/KubernetesOpenShift.md — GA Phase 5 (NFR-050)
  /grafana                 # provisioned self-observability dashboards  — Phase 0 (observability wiring)

/simulation
  /packs/demo-small        # 1 team, 3 sprints — CI smoke pack           — Phase 1
  /packs/demo-midsize      # 4 teams, 12 sprints — Day-1 default         — Phase 1
  /packs/demo-troubled     # injected delays/incidents — risk-agent dev  — Phase 1
  /packs/enterprise-large  # generator-based load-test pack              — Phase 1 (used heavily Phase 5)

/scripts
  /idea/eip-codestyle.xml  # committed IDE code style (matches Spotless) — Phase 0

/work
  /sprints/SPRINT-NN.md    # sprint plans (template: ./SprintExecutionGuide.md)
  /tasks/TASK-NNNN.md      # task specs — the prompt payload for engineering agents
  /handoffs/               # TASK-NNNN-<seq>.md session handoff notes
  /releases/RELEASE-vX.Y.Z.md  # go/no-go release records (./ReleaseManagement.md)
  debt-register.md         # DEBT-NNN entries (./TechnicalDebtPolicy.md)
  risk-register.md         # RISK-NNN entries (./RiskManagementPolicy.md)
```

Pack catalog and contents per [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §7; size rules for packs in [./RepositoryRules.md](./RepositoryRules.md) §6.

## 5. `/docs` tree (unchanged, for orientation)

| Path | Contents |
|---|---|
| `/docs/vision` | Vision, strategic pillars, non-goals |
| `/docs/product` | PRD (FR/NFR IDs), Personas, UserJourneys, FeatureCatalog, UseCases, AcceptanceCriteria, Roadmap |
| `/docs/architecture` | ArchitectureOverview, DomainModel, ComponentModel, DataFlow, DeploymentModel, SecurityModel, ObservabilityModel (+ `/generated`, `/adr` per §1) |
| `/docs/ai` | AgentArchitecture (the product's 18 runtime agents — not engineering roles), RAGArchitecture, MCPArchitecture |
| `/docs/engineering` | BackendPlan, FrontendPlan, DatabasePlan, ConnectorFramework, EventModel, APIDesign |
| `/docs/infrastructure` | LocalDevelopment, DockerCompose, KubernetesOpenShift |
| `/docs/testing` | TestingStrategy |
| `/docs/operations` | OperationsGuide |
| `/docs/implementation` | PhaseBasedImplementationPlan (stories `P<phase>-E<epic>-S<story>` — every TASK cites one) |

## 6. Structural invariants

1. New top-level directories MUST NOT be created without a CC-1-classed PR updating this document, `CODEOWNERS`, and [./ModuleOwnership.md](./ModuleOwnership.md) together.
2. New backend Gradle modules are an architecture decision: ADR + R-CA approval + a row in §2 + a `MODULE.md` before any code.
3. Flyway migrations exist only under `/backend/eip-app/src/main/resources/db/migration` ([../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §7); a migration file anywhere else fails G1.
4. Every module directory (nine backend modules + `/frontend`) carries a current `MODULE.md`; a PR changing a module's owned tables, topics, or endpoints MUST update its `MODULE.md` in the same PR (checked at G7).
5. `/work` files never contain product code; product directories never contain task state. The repo is the shared memory, but each layer stays in its lane ([./ContextManagementStrategy.md](./ContextManagementStrategy.md)).

## Related documents

- [./RepositoryRules.md](./RepositoryRules.md) — binding rules for everything in this tree
- [./ModuleOwnership.md](./ModuleOwnership.md) — ownership semantics and CODEOWNERS content
- [./BranchingStrategy.md](./BranchingStrategy.md) — how changes flow into this tree
- [./ContextManagementStrategy.md](./ContextManagementStrategy.md) — L1–L4 memory layers over these paths
- [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1 — authoritative module table
- [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §12 — authoritative frontend folders
- [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) — how this tree runs locally
