# CLAUDE.md — EIP Operating Manual

Permanent operating manual for every AI engineering session in this repository. Read this first, every session. The detailed law lives in [/engineering-operating-system](engineering-operating-system/README.md) (EOS); the product truth lives in [/docs](docs/). This file summarizes — it never overrides either.

## What this repository is

**Engineering Intelligence Platform (EIP)** — an on-premise, air-gap-capable, multi-tenant, AI-native platform that integrates enterprise SDLC tools (Jira, GitHub, GitLab, SonarQube, CI/CD, Prometheus, …), normalizes and correlates their data into one canonical model, computes flow/DORA/quality/risk analytics, and generates dashboards, reports, and AI-composed narratives — with hard ethical guardrails: **team-level insight only, never individual surveillance or ranking** (FR-057, NFR-071 — release-blocking).

Vision: [docs/vision/Vision.md](docs/vision/Vision.md) · Requirements: [docs/product/PRD.md](docs/product/PRD.md) · Build plan: [docs/implementation/PhaseBasedImplementationPlan.md](docs/implementation/PhaseBasedImplementationPlan.md)

## Canonical facts (never contradict; cite, don't restate)

| Fact | Value |
|---|---|
| Backend | Java 21, Spring Boot 3.x modular monolith (Spring Modulith) + deployable `eip-workers` |
| Modules (9) | eip-core, eip-tenancy, eip-connectors, eip-ingestion, eip-analytics, eip-ai, eip-reports, eip-workers, eip-app |
| Database | PostgreSQL 16 + pgvector, Flyway (`V<NNN>__`), RLS via `SET LOCAL app.tenant_id` (transaction-scoped, always) |
| Messaging | Kafka (KRaft), topics `eip.*` per [EventModel](docs/engineering/EventModel.md), DLQ = `<group>.dlq`, groups `eip.<module>.<purpose>`, at-least-once + idempotent consumers (dedup on `eventId` UUIDv7) |
| Cache/locks | Redis 7 (Redisson) — fail-open; platform stays correct on cache loss |
| Frontend | React 18 + TypeScript (strict) + Vite + TanStack Query + ECharts, pnpm, locale `en-US` |
| API | REST `/api/v1` only, OpenAPI 3 (springdoc), RFC 7807 errors, cursor pagination, additive-only changes |
| Identity | OIDC — Keycloak default; deny-by-default RBAC, permission catalog in eip-tenancy |
| Secrets | AES-256-GCM envelope encryption via secrets service — never plaintext, never in logs |
| AI (product) | LangChain4j; LLM/VectorStore/KMS/Connector SPIs, semver'd (NFR-060); pgvector default |
| Observability | OpenTelemetry → Prometheus/Grafana; metric names `eip_*` per [ObservabilityModel](docs/architecture/ObservabilityModel.md) |
| Phases | 0→v0.1 … 5→v1.0; exit criteria = [PRD §10](docs/product/PRD.md) |
| Key budgets | API p95 < 300 ms · dashboards p50 < 500 ms/p95 < 2 s · 100k events/h + 3× burst · webhook freshness 60 s p95 · Compose ≤ 15 min on 16 GB |

**Disambiguation:** the product ships 18 *runtime* AI agents (FR-082). Engineering roles that *build* the product (R-CA, R-IE, R-CR, …) are a different concept — see [AIAgentCatalog](engineering-operating-system/AIAgentCatalog.md). Never mix them.

## The ten laws (non-negotiable)

1. **Docs-first.** `/docs` is the source of record. Code follows docs; deviations land as doc+ADR changes first. Never let code silently diverge.
2. **Gates are law.** Every merge passes its required gates (G1–G3, G7 always; G4/G5/G6 by change class). No waivers except as [QualityGatePolicy](engineering-operating-system/QualityGatePolicy.md) allows.
3. **CI is truth.** Never claim tests pass without CI evidence. Unverifiable claims are review BLOCKERs.
4. **Single writer.** One agent owns a task; parallel tasks have disjoint write-sets. Never edit files owned by another in-flight task.
5. **Tenancy always.** Every tenant-owned table gets `tenant_id` + RLS; every new endpoint declares permissions; RLS is the backstop, not the mechanism.
6. **No individual metrics.** Nothing that ranks, scores, or surfaces individual developer activity ships — ever (NFR-071).
7. **Regression-test-first.** Bug fixes land the failing test before the fix (red→green evidence).
8. **Unobservable = unfinished.** New endpoints/consumers/jobs ship metrics, traces, structured logs, and alert/dashboard updates.
9. **Register or fix.** No unregistered shortcuts — file DEBT-NNN or do it right. No unrecorded decisions — file ADR-NNN.
10. **Everything in artifacts.** Knowledge lives in files (task specs, handoffs, ADRs, registers) — a session's unwritten context is lost by design.

## Session ritual

1. Read this file, then your task spec `/work/tasks/TASK-NNNN.md` and its context pack (ordered reading list — read exactly that, in order).
2. Verify branch state (`git status`, correct `feature/TASK-NNNN-slug` branch off `main`).
3. Work the task: small conventional commits `type(scope): summary [TASK-NNNN]`; run gates locally before pushing.
4. If blocked > 1 session: write an escalation record in the task file and stop.
5. End of session: task DONE → update task file; not DONE → write `/work/handoffs/TASK-NNNN-<seq>.md` (state, verified facts, next 3 steps, gotchas).

Full lifecycle: [DevelopmentLifecycle](engineering-operating-system/DevelopmentLifecycle.md) · Day-to-day discipline: [AIEngineeringGuide](engineering-operating-system/AIEngineeringGuide.md)

## Quality gates (summary)

| Gate | When | What |
|---|---|---|
| G0 Ready | before work | [DoR](engineering-operating-system/DefinitionOfReady.md) satisfied |
| G1 Build & Static | every PR | compile, lint/format, Modulith/ArchUnit boundaries, OpenAPI diff |
| G2 Tests | every PR | unit+integration+contract green, coverage ratchet |
| G3 Security | every PR | SAST, dependency/secret scan, isolation tests; [SecurityChecklist](engineering-operating-system/SecurityChecklist.md) for CC-2 |
| G4 Architecture | CC-1/CC-4 | owning architect (+R-CA for anchors) reviews contract diff |
| G5 Performance | CC-3 | NFR budgets, EXPLAIN evidence, load smoke — [PerformanceChecklist](engineering-operating-system/PerformanceChecklist.md) |
| G6 Observability | new paths | [ObservabilityRequirements](engineering-operating-system/ObservabilityRequirements.md) |
| G7 Documentation | every PR | docs impact applied; docs-lint green |
| G8 Review & Done | every PR | independent R-CR approval; [DoD](engineering-operating-system/DefinitionOfDone.md) |

Change classes CC-1..CC-7 (declare in every PR): contract-anchor, security-relevant, hot-path, schema/migration, AI-behavior, docs-only, standard — definitions in [QualityGatePolicy](engineering-operating-system/QualityGatePolicy.md). **Contract anchors** (CC-1, need G4 + two approvals): PRD, DomainModel, EventModel, APIDesign, ConnectorFramework SPI, SecurityModel, published SPI interfaces.

## Repository rules (summary)

- `main` is protected: PRs only, required checks, squash merge. Branches `feature|fix|refactor|docs|adr/TASK-NNNN-slug`, ≤ 5 days, PR ≤ ~400 net lines.
- Never commit: secrets, real tenant data, model weights, unredacted logs. Lockfiles and committed OpenAPI are versioned.
- Structure and ownership: [RepositoryStructure](engineering-operating-system/RepositoryStructure.md) · [ModuleOwnership](engineering-operating-system/ModuleOwnership.md) (CODEOWNERS maps paths → roles).
- New dependencies: owning-architect approval, license allow-list (Apache-2.0/MIT/BSD/EPL; no GPL runtime), air-gap fit — [DependencyManagement](engineering-operating-system/DependencyManagement.md).
- Work state lives in `/work` (sprints, tasks, handoffs, debt & risk registers); ADRs in `/docs/adr`.

## Coding standards (summary)

Java 21: records for values, sealed hierarchies for closed sets, constructor injection only, package-by-module, exceptions per BackendPlan's sealed taxonomy. SQL: snake_case, expand–contract for breaking changes, no edits to applied migrations. TypeScript: strict, no `any`, externalized strings. Tests: given/when/then, Testcontainers, no sleeps. Logs: structured JSON, no PII/secrets. Comments: constraints only, no narration. Enforced by Spotless/Prettier/ArchUnit in G1. Full rules: [CodingStandards](engineering-operating-system/CodingStandards.md) · [ArchitecturePrinciples](engineering-operating-system/ArchitecturePrinciples.md).

## Review process (summary)

Independent reviewer (R-CR) reads task spec + diff + cited docs only — never the author's reasoning. Verdicts: **BLOCKER / MAJOR / MINOR / NIT**. Author never merges without approval; CC-1 needs reviewer + owning architect (+R-CA). Bug-fix PRs show the regression test failing first. Checklist: [CodeReviewChecklist](engineering-operating-system/CodeReviewChecklist.md) · Validation ladder L0–L4: [AIValidationWorkflow](engineering-operating-system/AIValidationWorkflow.md).

## Definition of Ready / Done (summary)

**Ready (G0):** story + FR/AC refs · testable acceptance criteria · declared write-set · single-session context pack · dependencies resolved · size ≤ M. **Done:** all gates green · ACs demonstrated with evidence · tests, observability, docs landed · shortcuts filed as DEBT · task file updated. Full checklists: [DefinitionOfReady](engineering-operating-system/DefinitionOfReady.md) · [DefinitionOfDone](engineering-operating-system/DefinitionOfDone.md).

## Where to look

| Need | Read |
|---|---|
| What to build | [PRD](docs/product/PRD.md), [FeatureCatalog](docs/product/FeatureCatalog.md), [AcceptanceCriteria](docs/product/AcceptanceCriteria.md) |
| System shape | [ArchitectureOverview](docs/architecture/ArchitectureOverview.md), [DomainModel](docs/architecture/DomainModel.md), [ComponentModel](docs/architecture/ComponentModel.md) |
| Contracts | [EventModel](docs/engineering/EventModel.md), [APIDesign](docs/engineering/APIDesign.md), [ConnectorFramework](docs/engineering/ConnectorFramework.md), [DatabasePlan](docs/engineering/DatabasePlan.md) |
| Constraints | [SecurityModel](docs/architecture/SecurityModel.md), [ObservabilityModel](docs/architecture/ObservabilityModel.md), [TestingStrategy](docs/testing/TestingStrategy.md) |
| How we work | [EOS index](engineering-operating-system/README.md) — start at [EngineeringOperatingSystem](engineering-operating-system/EngineeringOperatingSystem.md) |
| Current state | `/work/sprints/` (current sprint), `/work/tasks/` (your task), `/work/handoffs/` (prior sessions) |

**Do not write application source code until Phase 0 tasks exist in `/work/tasks/` and you have claimed one.**
