# SPRINT-00 Task Specifications

The eight ready-to-claim task specs for SPRINT-00. This file is the authoritative backlog. **These are specifications, not implementations** — no application code, no source folders are created by writing this file; each spec *describes* the files its task will create when claimed. At claim time the spec is materialized into `/work/tasks/TASK-000N.md` per [DevelopmentLifecycle §4](../../engineering-operating-system/DevelopmentLifecycle.md).

Conventions: Story IDs from [PhaseImpl §5.1](../../docs/implementation/PhaseBasedImplementationPlan.md). Change classes CC-1..7 per [QualityGatePolicy §3](../../engineering-operating-system/QualityGatePolicy.md). Every spec's `Required documents` = the task's complete context pack (a session reads **only** this, plus the SPRINT-00 invariant prefix). `Forbidden documents` = the SPRINT-00 forbidden set ([Sprint00.md §5](../../program/Sprint00.md)) unless the spec narrows it further.

**Invariant prefix (every task, read in order — [ContextManagementStrategy §3](../../engineering-operating-system/ContextManagementStrategy.md)):** [/CLAUDE.md](../../CLAUDE.md) → the `/work/tasks/TASK-000N.md` task file → [../../program/Sprint00.md](../../program/Sprint00.md) → [DevelopmentLifecycle](../../engineering-operating-system/DevelopmentLifecycle.md) + [DefinitionOfReady](../../engineering-operating-system/DefinitionOfReady.md) + [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md) + [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md) → in-scope `MODULE.md`. The per-task `Required documents` below are **in addition** to this prefix.

**Universal forbidden set (all SPRINT-00 tasks):** all AI/RAG/MCP docs; ConnectorFramework; EventModel/DatabasePlan DDL; SecurityModel deep; APIDesign; ObservabilityModel; FrontendPlan; KubernetesOpenShift + DeploymentModel K8s sections; PhaseImpl §7–§10 ([Sprint00.md §5](../../program/Sprint00.md)).

---

## TASK-0001

- **Title:** Monorepo scaffolding — Gradle multi-module backend + Vite frontend + `/infra` + `/scripts`
- **Story:** P0-E1-S1 · **Lane:** L0 (serial prerequisite) · **Change class:** CC-7 · **Role:** R-IE (backend) under R-BA
- **Objective:** Create the canonical monorepo tree so every later task has a place to write. Empty, buildable skeletons for the nine backend modules + frontend, with editorconfig, license headers, and root build wiring — no business logic.
- **Scope:** Root `settings.gradle.kts` + `build.gradle.kts` + `buildSrc` convention plugins; empty Gradle modules for all nine `eip-*` per [BackendPlan §1](../../docs/engineering/BackendPlan.md); Vite + TypeScript `/frontend` skeleton; `/infra`, `/scripts` directories; `.editorconfig`, `.gitignore`, license headers, `LICENSE`; root `README.md` stub (expanded in TASK-0004). Directory tree exactly per [RepositoryStructure.md](../../engineering-operating-system/RepositoryStructure.md).
- **Out of scope:** any module's domain types (TASK-0005 owns eip-core); CI (TASK-0002); Compose (TASK-0003); CODEOWNERS (TASK-0007). No `main()` logic beyond a Spring Boot app class stub in `eip-app`.
- **Required documents:** [RepositoryStructure.md](../../engineering-operating-system/RepositoryStructure.md) (full), [RepositoryRules.md](../../engineering-operating-system/RepositoryRules.md) (full), [BranchingStrategy.md](../../engineering-operating-system/BranchingStrategy.md) (full), [CodingStandards.md](../../engineering-operating-system/CodingStandards.md) (full), [ArchitectureOverview.md §5](../../docs/architecture/ArchitectureOverview.md) (module map + dep rules), [BackendPlan.md §1](../../docs/engineering/BackendPlan.md) (Gradle module table). Reference-on-demand: [PhaseImpl §5.1–§5.2](../../docs/implementation/PhaseBasedImplementationPlan.md).
- **Forbidden documents:** universal set.
- **Files to create/modify:** `/settings.gradle.kts`, `/build.gradle.kts`, `/buildSrc/`, `/backend/eip-{core,tenancy,connectors,ingestion,analytics,ai,reports,workers,app}/build.gradle.kts` (empty), `/frontend/{package.json,vite.config.ts,tsconfig.json}`, `/.editorconfig`, `/.gitignore`, `/LICENSE`, `/README.md` (stub).
- **Dependencies:** none (root task).
- **Acceptance criteria:**
  1. `./gradlew build` succeeds on the empty module set (all nine modules resolve, zero sources compile cleanly).
  2. `pnpm --dir frontend install && pnpm --dir frontend build` succeeds on the Vite skeleton.
  3. The committed tree matches [RepositoryStructure.md](../../engineering-operating-system/RepositoryStructure.md) exactly (path-by-path); `pnpm`/`packageManager` pinned per CodingStandards.
  4. Module list is exactly the nine `eip-*` names — no extra, none missing.
- **Validation commands:** `./gradlew build` · `pnpm --dir frontend build` · `git ls-files | sort` (diff against RepositoryStructure tree).
- **Review gates:** G0, G1 (build, license allow-list, editorconfig), G2 (no tests yet — trivially green), G7 (README stub), G8 (R-CR). *No G4* — scaffolding sets structure but touches no contract anchor (eip-core content is TASK-0005/CC-1).
- **Estimated complexity:** S (≤ ½ session). Context ~14–18k.
- **Claim instructions:** First task of the sprint — claim before any other. `git switch -c feature/TASK-0001-monorepo-scaffolding` off `main`; commit `build(repo): scaffold monorepo tree [TASK-0001]`. Merge before L1/L2/L3 tasks start.
- **Definition of Done:** [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md) Code + Docs sections; specifically: builds green in CI (note: CI arrives in TASK-0002 — until then, local `./gradlew build` + `pnpm build` logs are the evidence, re-verified once TASK-0002 merges), tree matches RepositoryStructure, no warnings, README stub present.

---

## TASK-0002

- **Title:** CI pipeline — static, unit, integration, Modulith/ArchUnit stages + coverage gates + branch protection
- **Story:** P0-E1-S2 · **Lane:** L1 · **Change class:** CC-7 · **Role:** R-IE (infra) under R-DOA (co-owner R-SA: pipeline secrets)
- **Objective:** Wire the CI pipeline that enforces [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md) merge gates from this PR forward, so no ungated code ever reaches `main`.
- **Scope:** CI workflow (`/.github/workflows` or Jenkins) with stages: Static (Spotless/Checkstyle/ErrorProne, ESLint/tsc, gitleaks, license-check — G1+G3-auto), Unit (G2), Integration (Testcontainers harness — G2), Modulith/ArchUnit boundary stage (G1), coverage ratchet (per [TestingStrategy §1](../../docs/testing/TestingStrategy.md), §16); branch protection on `main` requiring these checks + PR + R-CR approval.
- **Out of scope:** the docs-lint job (TASK-0008); the Compose stack the integration stage will eventually use (TASK-0003 — integration tests use Testcontainers, not the dev stack); any application code.
- **Required documents:** [RepositoryRules.md](../../engineering-operating-system/RepositoryRules.md) (protection, gates), [BranchingStrategy.md](../../engineering-operating-system/BranchingStrategy.md), [QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md) (G1/G2/G3 specs — already in invariant prefix), [TestingStrategy.md §1, §16](../../docs/testing/TestingStrategy.md) (gate philosophy + CI stage map), [CodingStandards.md](../../engineering-operating-system/CodingStandards.md) (tool names). Reference-on-demand: [PhaseImpl §5.2 PR-2](../../docs/implementation/PhaseBasedImplementationPlan.md).
- **Forbidden documents:** universal set.
- **Files to create/modify:** `/.github/workflows/ci.yml` (or `/Jenkinsfile`), coverage config in `buildSrc` convention plugins, branch-protection config (as code where supported, else documented in `/docs`).
- **Dependencies:** TASK-0001 (needs the module tree to build).
- **Acceptance criteria:**
  1. CI runs Static + Unit + Integration + Modulith/ArchUnit stages on every PR and is green on the empty modules.
  2. A PR that fails Spotless/Checkstyle is **blocked** at the Static stage.
  3. Coverage ratchet is active (thresholds per TestingStrategy §1: ≥85% eip-core, ≥75% elsewhere) and configured to fail below threshold.
  4. `main` is protected: no direct push; PR + all required checks + one R-CR approval required.
- **Validation commands:** open a throwaway PR with a lint violation → observe Static stage red; fix → green. `gh pr checks` (or Jenkins UI) shows all stages. Branch-protection API/config diff.
- **Review gates:** G0, G1, G2, G3-auto, G7, G8 (R-CR); R-DOA owns G1.
- **Estimated complexity:** M (≤ 1 session). Context ~16–20k.
- **Claim instructions:** After TASK-0001 merges. `feature/TASK-0002-ci-pipeline`; commit `build(infra): CI pipeline with gate stages [TASK-0002]`. Disjoint write-set from TASK-0005/0006/0007; sequence before TASK-0003 in L1.
- **Definition of Done:** DoD Code + Process; CI green on empty modules (this task *is* the CI); the lint-block-then-pass demonstration recorded as evidence; branch protection verified.

---

## TASK-0003

- **Title:** Docker Compose dev stack — full infra + healthchecks + `make dev-up`
- **Story:** P0-E1-S3 · **Lane:** L1 · **Change class:** CC-7 · **Role:** R-IE (infra) under R-DOA
- **Objective:** One-command bootable dev stack (`make dev-up`) with every infra service healthy within the NFR-050 budget, so all later sprints have a real local platform.
- **Scope:** `/infra/docker-compose` with Postgres 16+pgvector, Kafka (KRaft), Redis 7, MinIO, Keycloak, OTel Collector, Prometheus, Grafana; healthchecks + dependency ordering; `.env` contract; `make dev-up`/`make dev-down`; Grafana provisioning stub; the §13 smoke checklist. Exactly per [DockerCompose.md §1–§4, §10, §13](../../docs/infrastructure/DockerCompose.md) and [LocalDevelopment.md](../../docs/infrastructure/LocalDevelopment.md).
- **Out of scope:** Kubernetes/OpenShift manifests (Phase 5 — forbidden); application service containers beyond what the dev stack needs (eip-app/workers run on host in dev per LocalDevelopment); observability dashboards for app metrics (Sprint-01 P0-E4-S3).
- **Required documents:** [DockerCompose.md §1–§4, §10, §13](../../docs/infrastructure/DockerCompose.md), [LocalDevelopment.md](../../docs/infrastructure/LocalDevelopment.md) (full), [RepositoryStructure.md](../../engineering-operating-system/RepositoryStructure.md) (`/infra` layout). Reference-on-demand: [PhaseImpl §5.2 PR-3, §5.3 exit line 4](../../docs/implementation/PhaseBasedImplementationPlan.md).
- **Forbidden documents:** universal set + explicitly [KubernetesOpenShift.md](../../docs/infrastructure/KubernetesOpenShift.md) and DeploymentModel K8s sections.
- **Files to create/modify:** `/infra/docker-compose/docker-compose.yml` (+ profile files), `/infra/docker-compose/.env.example`, `/infra/grafana/` provisioning stub, `/Makefile` (`dev-up`, `dev-down`).
- **Dependencies:** TASK-0001 (repo tree). Sequenced after TASK-0002 within L1 (declared order; disjoint write-set — `/infra/docker-compose` vs `/.github`).
- **Acceptance criteria:**
  1. `make dev-up` on a clean 16 GB host brings the core + observability profile to healthy in ≤ 15 min (**NFR-050**, [DockerCompose §10/§13](../../docs/infrastructure/DockerCompose.md)).
  2. Every service passes its healthcheck; ports/bucket names match the DockerCompose reference (8081 actuator, 8180 Keycloak HTTP / 9000 health, 3001 Grafana, `eip-artifacts`/`eip-ingest` buckets).
  3. `make dev-down` stops and cleans the stack.
  4. The §13 smoke checklist passes.
- **Validation commands:** `time make dev-up` (assert ≤ 15 min) · `docker compose -f infra/docker-compose/docker-compose.yml ps` (all healthy) · run the [DockerCompose §13](../../docs/infrastructure/DockerCompose.md) smoke steps · `make dev-down`.
- **Review gates:** G0, G1, G2 (compose-config lint / smoke), G7, G8. No G6 (no app telemetry yet).
- **Estimated complexity:** M. Context ~15–19k.
- **Claim instructions:** After TASK-0002. `feature/TASK-0003-compose-dev-stack`; commit `build(infra): compose dev stack + make dev-up [TASK-0003]`. Unblocks TASK-0004.
- **Definition of Done:** DoD Code + Docs; NFR-050 boot time recorded as evidence; smoke checklist green; `.env.example` documented.

---

## TASK-0004

- **Title:** Developer docs — README, onboarding guide, `make dev-up` bootstrap
- **Story:** P0-E1-S4 · **Lane:** L3 · **Change class:** CC-6 (docs) · **Role:** R-DE
- **Objective:** A new engineer is productive in under a day: README + onboarding guide covering clone → `make dev-up` → first PR, plus the contribution rules incl. gates.
- **Scope:** Expand the root `README.md`; `/docs` onboarding/day-1 guide; contribution section pointing at the EOS gates, branching, and CLAUDE.md session ritual. Content only — no automation.
- **Out of scope:** the docs-lint automation (TASK-0008); ADR files (TASK-0006); any spec content (that lives in `/docs`, unchanged).
- **Required documents:** [LocalDevelopment.md](../../docs/infrastructure/LocalDevelopment.md) (the day-1 journey to mirror), [RepositoryRules.md](../../engineering-operating-system/RepositoryRules.md), [BranchingStrategy.md](../../engineering-operating-system/BranchingStrategy.md), [DocumentationStandards.md](../../engineering-operating-system/DocumentationStandards.md), [CLAUDE.md](../../CLAUDE.md) (already in prefix — the ritual to reference). Reference-on-demand: [PhaseImpl §5.2 PR-10](../../docs/implementation/PhaseBasedImplementationPlan.md).
- **Forbidden documents:** universal set.
- **Files to create/modify:** `/README.md` (expand), `/docs/onboarding/` or `/CONTRIBUTING.md` (onboarding + contribution rules). No `/docs` spec files touched.
- **Dependencies:** TASK-0003 (`make dev-up` must exist to be documented). Soft-references TASK-0002 (gates) and TASK-0001 (tree).
- **Acceptance criteria:**
  1. A reader following the onboarding guide from a clean clone reaches a booted stack and an open PR without asking a human.
  2. The guide's `make dev-up` steps match TASK-0003 exactly (commands, ports, expected health).
  3. Contribution rules link the gates, branch naming, and the CLAUDE.md session ritual; docs-lint (once TASK-0008 lands) is green over the new docs.
- **Validation commands:** manual walkthrough of the onboarding steps on a clean clone; `markdownlint`/link-check over the new docs (once TASK-0008 exists).
- **Review gates:** G0, G7 (docs-lint), G8 (R-CR). Light G1 (no code).
- **Estimated complexity:** S. Context ~10–14k.
- **Claim instructions:** After TASK-0003. `docs/TASK-0004-developer-docs`; commit `docs(docs): onboarding + make dev-up guide [TASK-0004]`.
- **Definition of Done:** DoD Docs; onboarding walkthrough verified; links resolve; consistent with TASK-0003 commands.

---

## TASK-0005

- **Title:** `eip-core` domain skeleton — shared kernel base types, `WorkItem` supertype, `ExternalRef`, event-envelope types, Modulith boundary test + `MODULE.md`
- **Story:** P0-E2-S1 · **Lane:** L2 · **Change class:** **CC-1** (shared kernel is a contract anchor) · **Role:** R-IE (backend) under R-BA + R-CA
- **Objective:** Establish the shared-kernel base types every downstream module depends on — the canonical identity and envelope conventions — with a Modulith boundary test that keeps `eip-core` a leaf forever.
- **Scope:** `eip-core` module: canonical base types (value objects), `WorkItem` supertype (type enum `EPIC|FEATURE|STORY|TASK|BUG|INCIDENT_TICKET`), `ExternalRef` (sourceSystem, externalId, externalKey, url), event-envelope types (eventId UUIDv7, tenantId, source, entityType, entityId, eventType, occurredAt, ingestedAt, schemaVersion, payload, traceparent), UUIDv7 + `Instant`/UTC conventions; Spring Modulith `ApplicationModules.verify()` test + committed Documenter output; `/backend/eip-core/MODULE.md` charter. **Skeleton only — no business logic, no owned tables.** Per [DomainModel.md §1–§2](../../docs/architecture/DomainModel.md) (principles + identity per ADR-014/AD-14 externalId-vs-externalKey).
- **Out of scope:** any persistence / Flyway (Sprint-01, forbidden here); normalizers, connectors, analytics (later phases); any table ownership (eip-core owns infra tables only *conceptually* per ADR-012 — DDL is Sprint-01).
- **Required documents:** [DomainModel.md §1–§2](../../docs/architecture/DomainModel.md), [ArchitectureOverview.md §5](../../docs/architecture/ArchitectureOverview.md) (module boundaries the Modulith test enforces), [CodingStandards.md](../../engineering-operating-system/CodingStandards.md) (records, sealed types, UUIDv7), [BackendPlan.md §3](../../docs/engineering/BackendPlan.md) (Modulith usage — reference), [ContextManagementStrategy §6](../../engineering-operating-system/ContextManagementStrategy.md) (MODULE.md template). Reference-on-demand: [ArchitectureDecisionUpdates.md](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) AD-14 (identity).
- **Forbidden documents:** universal set — in particular **EventModel.md** (envelope *usage* on Kafka is Sprint-01; eip-core defines the *types* only, from DomainModel §1–§2 + the envelope field list in CLAUDE.md) and **DatabasePlan.md** (no DDL this task).
- **Files to create/modify:** `/backend/eip-core/src/main/java/...` (base types, WorkItem, ExternalRef, envelope — types only), `/backend/eip-core/src/test/java/...ModularityTests` (`ApplicationModules.verify()`), `/backend/eip-core/MODULE.md`, committed Modulith Documenter output.
- **Dependencies:** TASK-0001 (module skeleton exists).
- **Acceptance criteria:**
  1. `ApplicationModules.of(...).verify()` passes; `eip-core` has zero dependencies on any other `eip-*` module (leaf invariant).
  2. Base types compile and are `record`/`sealed` per CodingStandards; UUIDv7 + UTC `Instant` conventions enforced by a unit test.
  3. `ExternalRef` distinguishes immutable `externalId` from mutable `externalKey` (AD-14); envelope type carries exactly the 11 canonical fields.
  4. `MODULE.md` present, accurate (purpose, exported types, invariants, dependencies=none).
  5. A test/PR that introduces an outbound dependency from `eip-core` to another module **fails** `verify()` (leaf-boundary demonstration — feeds the sprint demo).
- **Validation commands:** `./gradlew :backend:eip-core:test` · `./gradlew :backend:eip-core:check` · inspect committed Documenter diff.
- **Review gates:** G0 (**+ CC-1 DoR: R-CA approach pre-approval recorded**), G1 (Modulith `verify()` + ArchUnit), G2 (unit + coverage ≥85%), **G4 (R-CA + R-BA — contract anchor)**, G7 (MODULE.md), **G8 (two approvals: R-CR + R-CA for CC-1)**.
- **Estimated complexity:** M. Context ~16–20k.
- **Claim instructions:** After TASK-0001. **Requires R-CA approach pre-approval before READY** (CC-1 DoR, [DefinitionOfReady §2](../../engineering-operating-system/DefinitionOfReady.md)). `feature/TASK-0005-eip-core-skeleton`; commit `feat(eip-core): shared-kernel base types + Modulith boundary test [TASK-0005]`. Disjoint write-set (`/backend/eip-core`) — runs parallel to L1/L3.
- **Definition of Done:** DoD Code + Tests + Docs; Modulith `verify()` green in CI; coverage ratchet met; MODULE.md accurate; the leaf-violation-blocked demonstration recorded; ADR reference if any design choice deviates from DomainModel (none expected — skeleton follows spec).

---

## TASK-0006

- **Title:** ADR backfill — author `/docs/adr/ADR-001..020`
- **Governance task** (readiness condition 4, [ImplementationReadinessDecision §3/§8](../../reviews/architecture-readiness/ImplementationReadinessDecision.md)) · **Lane:** L3 · **Change class:** CC-6 (docs; **G4** — R-CA approves ADR content) · **Role:** R-CA + R-DE
- **Objective:** Materialize the decision record: ADR-001–014 (backfilled from the ArchitectureOverview §7 summary) and ADR-015–020 (from the readiness review) as individual files under `/docs/adr/`, so every architectural decision has a durable, linkable home.
- **Scope:** 20 ADR files per the [ADRProcess §4](../../engineering-operating-system/ADRProcess.md) template (status Accepted, approver R-CA, context/decision/consequences/alternatives); each ADR-001–014 carries the "Backfilled in Phase 0 from ArchitectureOverview §7" note; ADR-015–020 authored from [ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md). Add a relative link from each [ArchitectureOverview §7](../../docs/architecture/ArchitectureOverview.md) index row to its file.
- **Out of scope:** any *new* decision (this is backfill of already-Accepted decisions — new ADRs number from ADR-021 later); CODEOWNERS (TASK-0007); the docs-lint job (TASK-0008).
- **Required documents:** [ADRProcess.md §4](../../engineering-operating-system/ADRProcess.md) (format + numbering), [ArchitectureOverview.md §7](../../docs/architecture/ArchitectureOverview.md) (ADR-001..020 summary index — the backfill source), [ArchitectureDecisionUpdates.md §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) (ADR-015..020 text), [DocumentationStandards.md](../../engineering-operating-system/DocumentationStandards.md) (ID stability, links). Reference-on-demand: [ImplementationReadinessDecision.md §8](../../reviews/architecture-readiness/ImplementationReadinessDecision.md).
- **Forbidden documents:** universal set (the ADR *content* is already summarized in ArchitectureOverview §7 + ArchitectureDecisionUpdates — do **not** open the deep AI/connector/DB specs to write the ADRs; the summaries + readiness doc are sufficient and authoritative).
- **Files to create/modify:** `/docs/adr/ADR-001-...md` … `/docs/adr/ADR-020-...md` (20 files), `/docs/architecture/ArchitectureOverview.md` §7 (add per-row links). No other spec content changes.
- **Dependencies:** TASK-0001 (repo conventions). Largely independent — `/docs` already exists (baselined).
- **Acceptance criteria:**
  1. `/docs/adr/ADR-001..020` all exist, three-digit unpadded-free IDs, ADRProcess §4 template, status Accepted, approver R-CA.
  2. Each ADR-015–020 content matches its [ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) row (decision, forced-by, alternatives).
  3. Every ArchitectureOverview §7 row links to its ADR file; every `ADR-0NN` reference elsewhere in `/docs` resolves (feeds TASK-0008 docs-lint).
  4. No ID renumbered; no new decision introduced.
- **Validation commands:** `ls docs/adr/ADR-0{01..20}-*.md | wc -l` (=20) · link-resolution check over `/docs` for `ADR-0NN` refs · R-CA content review.
- **Review gates:** G0, **G4 (R-CA — ADR approval)**, G7 (docs-lint), G8 (R-CR + R-CA).
- **Estimated complexity:** M. Context ~18–22k.
- **Claim instructions:** After TASK-0001. `adr/TASK-0006-adr-backfill`; commit `docs(docs): backfill ADR-001..020 [TASK-0006]`. Disjoint write-set (`/docs/adr` + ArchOverview §7). Produces the ADRs that TASK-0008 lints.
- **Definition of Done:** DoD Docs; 20 files present + Accepted; §7 links added; docs-lint green (once TASK-0008 exists); satisfies readiness condition 4.

---

## TASK-0007

- **Title:** CODEOWNERS — map every path to its owning role
- **Governance task** · **Lane:** L3 · **Change class:** CC-6 · **Role:** R-CA
- **Objective:** Commit the ready-to-use CODEOWNERS so every path has an accountable owning role and CI can enforce owner review.
- **Scope:** `/CODEOWNERS` mapping paths → roles exactly per [ModuleOwnership.md §4](../../engineering-operating-system/ModuleOwnership.md) (the module→role map and the CODEOWNERS content it defines). Cover every top-level path in the scaffolded tree.
- **Out of scope:** branch protection (TASK-0002 owns it); the ownership *policy* (already in ModuleOwnership — this task only materializes the file); docs-lint.
- **Required documents:** [ModuleOwnership.md §4](../../engineering-operating-system/ModuleOwnership.md) (CODEOWNERS content + syntax), [RepositoryStructure.md](../../engineering-operating-system/RepositoryStructure.md) (paths to cover). Reference-on-demand: [AIAgentCatalog.md](../../engineering-operating-system/AIAgentCatalog.md) (role IDs).
- **Forbidden documents:** universal set.
- **Files to create/modify:** `/CODEOWNERS` (or `/.github/CODEOWNERS`).
- **Dependencies:** TASK-0001 (paths must exist to be owned meaningfully).
- **Acceptance criteria:**
  1. Every top-level path in the tree has a CODEOWNERS entry (codeowners-coverage check: no unowned path).
  2. Path→role mapping matches [ModuleOwnership.md §1/§4](../../engineering-operating-system/ModuleOwnership.md) exactly.
  3. CODEOWNERS syntax is valid (parsed by the platform without error).
- **Validation commands:** codeowners-coverage script (every tracked path matched) · platform CODEOWNERS validator · diff against ModuleOwnership §4.
- **Review gates:** G0, G7, G8 (R-CR + R-CA).
- **Estimated complexity:** S. Context ~10–13k.
- **Claim instructions:** After TASK-0001. `feature/TASK-0007-codeowners`; commit `build(repo): CODEOWNERS path→role map [TASK-0007]`. Disjoint write-set (`/CODEOWNERS`).
- **Definition of Done:** DoD Docs + Process; coverage check green; matches ModuleOwnership; validated by the platform.

---

## TASK-0008

- **Title:** docs-lint CI automation — links, ID references, canonical-value drift
- **Governance task** · **Lane:** L3 · **Change class:** CC-7 · **Role:** R-DE + R-DOA
- **Objective:** Automate the G7 docs-lint gate so documentation stays consistent forever: broken-link detection, requirement-ID resolution, FeatureCatalog/Roadmap count reconciliation, and canonical-value drift greps — the four checks named in [DocumentationStandards §L4](../../engineering-operating-system/DocumentationStandards.md).
- **Scope:** A CI job (added to the TASK-0002 pipeline) + `/scripts` lint scripts implementing: (1) relative-link resolution across `/docs`, `/engineering-operating-system`, `/reviews`, `/program`, `/sprints`; (2) FR/NFR/FEAT/AC/UC/ADR reference resolution; (3) FeatureCatalog/Roadmap count reconciliation; (4) canonical-value greps (app.tenant_id, `<group>.dlq`, NFR figures, `en-US`, pnpm). Fails the PR on any violation.
- **Out of scope:** authoring any docs (TASK-0004/0006); fixing existing violations (baseline is already clean — this locks it in); application code.
- **Required documents:** [DocumentationStandards.md](../../engineering-operating-system/DocumentationStandards.md) (the L4 check definitions + docs-lint spec), [QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md) (G7 — in prefix), [TestingStrategy.md §16](../../docs/testing/TestingStrategy.md) (CI stage placement). Reference-on-demand: [DocumentationQualityReview.md §5](../../docs/reviews/DocumentationQualityReview.md) (the four checks' origin).
- **Forbidden documents:** universal set.
- **Files to create/modify:** `/scripts/docs-lint/` (lint scripts), `/.github/workflows/ci.yml` (add docs-lint stage), config for the canonical-value list.
- **Dependencies:** TASK-0002 (CI pipeline to attach the stage to); lints the outputs of TASK-0006 (ADRs) and TASK-0007 (CODEOWNERS) — should run after they merge so the checks pass on real content.
- **Acceptance criteria:**
  1. docs-lint runs on every PR as a G7 stage and is green over the current (baseline-clean) doc set.
  2. A PR adding a broken relative link is **blocked**; fix → green.
  3. A PR adding a dangling `FR-999`/`ADR-099` reference is **blocked**.
  4. A PR introducing a canonical-value drift (e.g. `eip.tenant_id`) is **blocked**.
  5. All four DocumentationStandards §L4 checks are implemented and wired.
- **Validation commands:** run each lint script locally over the repo (all green) · open throwaway PRs injecting (a) a broken link, (b) a dangling ID, (c) a drift value → each blocked, each fixable to green.
- **Review gates:** G0, G1 (script lint), G2 (script self-tests), G7, G8 (R-CR).
- **Estimated complexity:** M. Context ~16–20k.
- **Claim instructions:** After TASK-0002 (and ideally after TASK-0006/0007 so it lints real content). `feature/TASK-0008-docs-lint`; commit `build(infra): docs-lint CI automation [TASK-0008]`.
- **Definition of Done:** DoD Code + Process; four checks implemented + wired to G7; block-then-pass demonstrations recorded; green over the baseline doc set.

---

## Related documents

- [Sprint00Plan.md](./Sprint00Plan.md) · [Sprint00Backlog.md](./Sprint00Backlog.md) · [TaskDependencyGraph.md](./TaskDependencyGraph.md)
- [Sprint00DefinitionOfReadyCheck.md](./Sprint00DefinitionOfReadyCheck.md) · [Sprint00ExecutionOrder.md](./Sprint00ExecutionOrder.md) · [Sprint00ReviewChecklist.md](./Sprint00ReviewChecklist.md)
- [../../program/Sprint00.md](../../program/Sprint00.md) · [../../program/ContextManifest.md](../../program/ContextManifest.md)
- [DefinitionOfReady](../../engineering-operating-system/DefinitionOfReady.md) · [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md) · [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md)
