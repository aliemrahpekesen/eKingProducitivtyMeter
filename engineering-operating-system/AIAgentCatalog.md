# AI Agent Catalog

This catalog enumerates the 21 engineering roles that build the Engineering Intelligence Platform (EIP). It is read by every engineering agent at session bootstrap to identify its role, by R-TPM when staffing sprints and lanes, and by any human deciding which role to occupy. The full nine-attribute role cards live in [AgentResponsibilities.md](./AgentResponsibilities.md); this document defines the roster, the team topology, per-phase staffing, and how humans map onto roles.

## 1. Engineering roles are not product agents

The PRODUCT ships 18 runtime AI agents (Sprint Review Agent, Validation Agent, Delivery Risk Agent, … — FR-082 in [../docs/product/PRD.md](../docs/product/PRD.md); architecture in [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md)). The roles in this catalog are **engineering agent roles**: AI agents and humans that BUILD the product. The two sets MUST never be conflated.

| Dimension | Product runtime agents (18) | Engineering roles (21) |
|---|---|---|
| Defined in | [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), FR-082 | This catalog + [AgentResponsibilities.md](./AgentResponsibilities.md) |
| Run where | Inside EIP (`eip-ai`, LangChain4j, `eip.ai.jobs`) | In engineering sessions against this repository |
| Governed by | Agent runtime budgets and guardrails (FR-080, FR-088) | Quality gates G0–G8, release gates RG1–RG4 |
| Naming rule | "runtime agent" / "product agent" | "engineering role" / "engineering agent" |

Every EOS document MUST use the right-hand vocabulary for builders. A sentence in which "agent" could mean either set MUST be rewritten.

## 2. Role roster

Authority levels: **A1** binding platform-wide · **A2** binding within domain · **A3** executes/advises · **A4** independent blocking verdict.

| ID | Role | Tier | Authority | Mission (one line) |
|---|---|---|---|---|
| R-CA | Chief Architect | Governance | A1 | Owns architectural integrity, ADR approval, contract-anchor changes, cross-module arbitration |
| R-PO | Product Owner | Governance | A1 (scope) | Owns PRD/scope/priority; accepts features against ACs; guards anti-goals (FR-057) |
| R-TPM | Technical Program Manager | Governance | A1 (schedule) | Owns sprint plans, task creation/sequencing, dependency management across lanes, status truth |
| R-BA | Backend Architect | Domain | A2 | Owns eip-core/eip-tenancy/eip-app/eip-workers design conformance (BackendPlan) |
| R-FA | Frontend Architect | Domain | A2 | Owns /frontend architecture (FrontendPlan), UX consistency, accessibility (FR-143) |
| R-AIA | AI Architect | Domain | A2 | Owns eip-ai design: agent runtime, RAG, MCP, LLM SPI (docs/ai/*) |
| R-DA | Data Architect | Domain | A2 | Owns canonical domain model semantics, normalization rules, correlation (DomainModel) |
| R-DBA | Database Architect | Domain | A2 | Owns physical PostgreSQL design, RLS policies, migrations, partitioning, pgvector (DatabasePlan) |
| R-CNA | Connector Architect | Domain | A2 | Owns Connector SPI + connector implementations conformance (ConnectorFramework) |
| R-DOA | DevOps Architect | Domain | A2 | Owns CI/CD pipelines, Compose/K8s manifests, air-gap packaging (infrastructure docs) |
| R-SA | Security Architect | Domain | A2+A4 | Owns SecurityModel conformance; blocking verdict on security gates and releases |
| R-PA | Platform Architect | Domain | A2 | Owns tenancy, quotas, Kafka/Redis/MinIO platform services, module boundaries with R-CA |
| R-QAA | QA Architect | Domain | A2 | Owns TestingStrategy conformance: test pyramid, golden datasets, contract tests, AI evals |
| R-PE | Performance Engineer | Domain | A3+A4(G5) | Owns performance budgets, load tests, regression thresholds; blocking verdict on G5 |
| R-OE | Observability Engineer | Domain | A3+A4(G6) | Owns ObservabilityModel conformance: metric/trace/log coverage, dashboards, alerts, SLOs |
| R-DE | Documentation Engineer | Execution | A3+A4(G7) | Owns /docs sync, docs-lint, DocumentationStandards enforcement |
| R-IE | Implementation Engineer | Execution | A3 | Implements tasks to spec (variants: backend/frontend/AI/connector — same role card) |
| R-TE | Test Engineer | Execution | A3 | Writes/extends tests per TestingChecklist; owns regression tests and golden data updates |
| R-RE | Refactoring Engineer | Execution | A3 | Executes registered refactors behavior-preservingly per RefactoringPolicy |
| R-CR | Code Reviewer | Assurance | A4 | Independent review verdict on every PR; fresh-context, diff+spec only |
| R-RM | Release Manager | Assurance | A4 | Owns release trains, go/no-go, release gates RG1–RG4, rollback decisions |

IDs, tiers, authority levels, and missions are canonical. No document, task, or prompt may rename, renumber, or add roles without an update to this catalog approved by R-CA.

## 3. Team topology

### 3.1 Tiers

| Tier | Roles | Function |
|---|---|---|
| Governance (3) | R-CA, R-PO, R-TPM | Platform-wide binding decisions: architecture (R-CA), scope (R-PO), schedule (R-TPM). They do not write feature code. |
| Domain (12) | R-BA, R-FA, R-AIA, R-DA, R-DBA, R-CNA, R-DOA, R-SA, R-PA, R-QAA, R-PE, R-OE | Binding within their domain (A2); design review, gate ownership/feeding, lane supervision. Module ownership per [ModuleOwnership.md](./ModuleOwnership.md). |
| Execution (4) | R-DE, R-IE, R-TE, R-RE | Do the work: implement, test, refactor, keep /docs synchronized. One task claim per session (single-writer rule, [DevelopmentLifecycle.md](./DevelopmentLifecycle.md)). |
| Assurance (2) | R-CR, R-RM | Independent verdicts: per-PR review (G8) and per-release go/no-go (RG1–RG4). Structurally separated from authorship. |

### 3.2 Parallel implementation lanes

Work parallelizes through **lanes**. A lane is one R-IE + one R-TE pair working under exactly one supervising domain architect on one module set.

Lane rules (normative):

1. Every lane MUST be declared in the sprint plan (`/work/sprints/SPRINT-NN.md`) with its supervising architect and its module set.
2. Lanes scheduled in parallel MUST have disjoint write-sets (files/modules) or an explicit ordering declared in the sprint plan — the single-writer rule of [DevelopmentLifecycle.md](./DevelopmentLifecycle.md).
3. The lane's R-IE and R-TE work the same tasks: R-IE implements, R-TE writes/extends tests per [TestingChecklist.md](./TestingChecklist.md). They MAY be the same session for size-S tasks; for CC-1/CC-2 changes the regression-test authorship SHOULD be a separate R-TE session.
4. A domain architect MAY supervise up to three lanes; beyond that, R-TPM MUST split the domain across sprints or add a co-owning architect per [ModuleOwnership.md](./ModuleOwnership.md).
5. Cross-lane needs are never resolved by direct edits into another lane's write-set: they become dependency-linked TASKs sequenced by R-TPM.

### 3.3 The independent R-CR pool

R-CR is a pool, not a lane member. Assignment rules:

- Every PR gets an R-CR review (G8). CC-1 PRs need two approvals: R-CR + owning architect (+ R-CA), per [QualityGatePolicy.md](./QualityGatePolicy.md).
- An R-CR session MUST have fresh context: it reads the task spec, the diff, and the cited docs — never the author's session reasoning or handoffs.
- An R-CR session MUST NOT review a PR it authored or co-authored in any prior session, and MUST NOT push commits to the PR under review (comments and verdicts only).
- Verdict scale: BLOCKER · MAJOR · MINOR · NIT, as defined in [CodeReviewChecklist.md](./CodeReviewChecklist.md).

### 3.4 Escalation chain

Default chain: **R-IE/R-TE → owning domain architect → R-CA → human repository owner.** Specializations:

| Dispute | Resolver |
|---|---|
| Disagreement between two architects | R-CA |
| Scope or priority dispute | R-PO |
| Schedule dispute | R-TPM |
| Security dispute | R-SA holds an A4 block; override only by the human repository owner, recorded in the task file |

Escalations are written as Escalation records in the task file (template in [DevelopmentLifecycle.md](./DevelopmentLifecycle.md)); verbal-only (chat-only) escalations do not exist — anything not written down is lost by design ([ContextManagementStrategy.md](./ContextManagementStrategy.md)).

## 4. Per-phase staffing

Phases 0–5 map to platform versions v0.1 → v1.0 ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §3); phase exit criteria are PRD §10 ([../docs/product/PRD.md](../docs/product/PRD.md)). Legend: **●** lead (owns the phase-critical path in their area) · **◐** active (regular task/gate work) · **○** standby (gate duty only, engaged on demand) · **—** dormant (not staffed).

| Role | P0 Foundations (v0.1) | P1 Ingestion (v0.2) | P2 Analytics (v0.3) | P3 AI Core (v0.4) | P4 Agents+MCP (v0.5) | P5 Hardening (v1.0) |
|---|---|---|---|---|---|---|
| R-CA | ● (ADR-001..014 backfill, boundaries) | ◐ | ◐ | ◐ | ◐ | ◐ |
| R-PO | ● (scope baseline, FR-057 guard) | ◐ | ● (anti-surveillance exit check) | ◐ | ◐ | ◐ |
| R-TPM | ● | ● | ● | ● | ● | ● |
| R-BA | ● (tenancy/RBAC/audit/secrets) | ● (workers chassis, outbox) | ◐ | ◐ (reports runtime) | ◐ | ◐ (backup/restore, HA) |
| R-FA | ○ (scaffold review) | ◐ (frontend skeleton) | ● (FR-065–FR-074) | ◐ (risk views FR-066, agent UI) | ◐ (artifact library UI) | ◐ (WCAG audit FR-143) |
| R-AIA | — | — | ○ (RAG groundwork review) | ● (FR-080–FR-101) | ● (FR-082, FR-104–FR-108) | ◐ |
| R-DA | ◐ (DomainModel baseline) | ● (FR-034–FR-036 normalization) | ● (metric semantics FR-050–FR-062) | ◐ | ◐ | ◐ |
| R-DBA | ● (Flyway baseline, RLS FR-128/131) | ◐ (partitioning, checkpoints) | ◐ (read models, `rm_*`) | ◐ (pgvector) | ○ | ● (HA, replicas NFR-020/022) |
| R-CNA | — | ● (SPI + Jira/GitHub/simulation FR-001–FR-004) | ◐ (FR-005 connectors) | ○ | ○ | ● (FR-006/FR-007) |
| R-DOA | ● (CI, Compose NFR-050) | ◐ | ◐ | ◐ | ◐ | ● (K8s/OpenShift GA FR-132) |
| R-SA | ● (FR-113, FR-121–FR-123) | ◐ | ◐ (isolation tests NFR-041) | ● (guardrails FR-088) | ● (MCP deny-by-default FR-106) | ● (security certification, RG2) |
| R-PA | ● (tenancy platform, Kafka baseline) | ◐ (topics, DLQs) | ◐ | ◐ (quotas FR-130) | ○ | ◐ |
| R-QAA | ● (test pyramid, CI gates) | ◐ | ◐ (golden datasets) | ◐ (AI eval suite) | ◐ | ◐ |
| R-PE | ○ (budget baselines) | ● (NFR-003 load test) | ● (NFR-010 at NFR-002 volume) | ◐ (NFR-013) | ◐ | ● (full-scale targets, RG3) |
| R-OE | ● (FR-126 self-observability) | ◐ (ingest lag FR-040) | ◐ | ◐ (AI budget dashboards) | ◐ | ◐ (RG4 operability) |
| R-DE | ● (docs-lint setup, G7) | ◐ | ◐ | ◐ | ◐ | ◐ |
| R-IE | ◐ (2 lanes) | ● (3 lanes) | ● (4 lanes) | ● (4 lanes) | ● (4–5 lanes) | ● (3–4 lanes) |
| R-TE | ◐ | ● | ● | ● | ● | ● |
| R-RE | — | — | ◐ (debt paydown ≤ 15% capacity) | ◐ | ◐ | ◐ |
| R-CR | ◐ | ● (every PR) | ● | ● | ● | ● |
| R-RM | ○ (v0.1 release) | ◐ | ◐ | ◐ | ◐ | ● (v1.0 go/no-go) |

### 4.1 Lane composition per phase

| Phase | Lanes (supervising architect → focus) |
|---|---|
| P0 | R-BA → tenancy/RBAC/audit/secrets (FR-113, FR-120–FR-123, FR-128); R-DOA → CI + Compose stack + observability bootstrap (FR-126, NFR-050) |
| P1 | R-CNA → Connector SPI + Jira/GitHub/simulation (FR-001–FR-004); R-CNA (R-DA co-owns) → staging + normalization + domain events (FR-030–FR-036); R-BA → workers chassis, outbox relay, checkpoints |
| P2 | R-DA → metric engine + read models (FR-050–FR-052, FR-056–FR-062); R-FA → dashboards (FR-065–FR-074); R-CNA → GitLab/SonarQube/Generic CI-CD/Prometheus (FR-005); R-BA → dashboard APIs |
| P3 | R-AIA → LLM SPI + RAG (FR-084–FR-085, FR-095–FR-101); R-AIA → Sprint Review/Release Notes/Delivery Risk agents (FR-083); R-BA + R-AIA → report engine (FR-110, FR-114, FR-115); R-FA → risk views + agent UI (FR-066, FR-091) |
| P4 | R-AIA → remaining canonical agents (FR-082); R-AIA → MCP client/server (FR-104–FR-108); R-BA + R-AIA → presentations/diagrams/scheduling (FR-111–FR-118); R-FA → artifact library UI (FR-118) |
| P5 | R-DOA → K8s/OpenShift GA (FR-132, NFR-020/022); R-CNA → remaining connectors (FR-006/FR-007); R-BA + R-DBA → backup/restore + upgrade paths (FR-141, NFR-021); R-PE-directed, R-BA-owned → full-scale performance (NFR-001–NFR-004) |

R-TPM MUST re-derive actual lanes each sprint from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) stories; this table is the default shape, not a substitute for the sprint plan.

## 5. Mapping a human onto a role

Any role MAY be occupied by a human or an AI engineering agent — the role card, gates, artifacts, and escalation rules are identical. Rules:

1. A human occupying a role MUST work through the same artifacts: task specs, PR template, handoff notes, escalation records ([DevelopmentLifecycle.md](./DevelopmentLifecycle.md) templates). No side channels; decisions made in conversation MUST be written into the task file before they take effect.
2. A human MAY hold multiple roles concurrently, with two hard separations: never R-CR on a PR they authored in any role, and never sole approver of a gate on their own change (G3/G5/G6/G7/G8 self-approval is prohibited).
3. The human repository owner is above the chain (§3.4) and is the only party who can override an R-SA A4 security block — the override MUST be recorded in the task file.
4. Human checkpoints are mandatory at L4 of the AI validation ladder ([AIValidationWorkflow.md](./AIValidationWorkflow.md)): phase boundaries, releases, and RG2.
5. Default human seats when only a few humans are available, in priority order: (1) repository owner above R-CA, (2) R-PO acceptance at RG1, (3) R-SA verdict at RG2. All other roles run AI-first.

## Related documents

- [AgentResponsibilities.md](./AgentResponsibilities.md) — full nine-attribute role cards and the capability RACI matrix
- [AIEngineeringGuide.md](./AIEngineeringGuide.md) — day-in-the-life manual for engineering agents
- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — the EOS constitution
- [DevelopmentLifecycle.md](./DevelopmentLifecycle.md) — task lifecycle, artifact templates, single-writer rule
- [ModuleOwnership.md](./ModuleOwnership.md) — path/module → owning role map
- [QualityGatePolicy.md](./QualityGatePolicy.md) — G0–G8 and RG1–RG4 in full
- [AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) — how roles exchange artifacts
- [../docs/product/PRD.md](../docs/product/PRD.md) — FR/NFR source of record; §10 phase exit criteria
- [../docs/product/Roadmap.md](../docs/product/Roadmap.md) — phase → version mapping
- [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md) — the product's 18 runtime agents (NOT engineering roles)
