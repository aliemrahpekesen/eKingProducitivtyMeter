# Module Ownership

This document is the authoritative ownership map of the EIP monorepo: which engineering role owns which module or path, what ownership obligates, the exact Phase 0 `CODEOWNERS` content, the ownership transfer protocol, and the orphaned-code rule. R-TPM consults it when assigning tasks; R-CR consults it to route G4 approvals; every agent consults it before touching a module it does not own. Roles (R-XX) are engineering agent roles from [./AIAgentCatalog.md](./AIAgentCatalog.md) — never the product's 18 runtime agents ([../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md)).

## 1. Ownership map

| Module / path | Owning role | Co-owner (scope of co-ownership) | Governing spec |
|---|---|---|---|
| `/backend/eip-core` | R-BA | R-CA (shared-kernel/SPI surface is contract-anchor territory) | [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1 |
| `/backend/eip-tenancy` | R-BA | R-PA (tenancy model, quotas); R-SA advises on RBAC/audit (CC-2) | [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) |
| `/backend/eip-app` | R-BA | R-SA (security filter chain paths, CC-2) | [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) |
| `/backend/eip-workers` | R-BA | R-DOA (deployment/runtime topology) | [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §9 |
| `/backend/eip-connectors` | R-CNA | — | [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md) |
| `/backend/eip-ingestion` | R-CNA | R-DA (normalization rules, canonical mapping) | [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) |
| `/backend/eip-analytics` | R-DA (metric semantics) | R-BA (runtime, query side) | [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) |
| `/backend/eip-ai` | R-AIA | — | [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md), [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md) |
| `/backend/eip-reports` | R-BA | R-AIA (report composition via agents) | [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1 |
| DB schema & migrations (`/backend/eip-app/src/main/resources/db/migration`) | R-DBA | owning module role (table semantics) | [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) |
| `/frontend` | R-FA | — | [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) |
| `/infra`, `/.github`, `/Makefile`, `/scripts` | R-DOA | R-SA (secrets handling in pipelines) | [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md), [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md), [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md) |
| `/simulation` | R-CNA | R-QAA (golden/test data fitness) | [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §7 |
| `/docs` (except `/docs/adr`) | R-DE | domain architects (technical accuracy of their sections) | [./DocumentationStandards.md](./DocumentationStandards.md) |
| `/docs/adr` | R-CA | — | [./ADRProcess.md](./ADRProcess.md) |
| `/engineering-operating-system`, `/CLAUDE.md`, `/CODEOWNERS` | R-CA | — | this bundle |
| `/work` | R-TPM | — | [./SprintExecutionGuide.md](./SprintExecutionGuide.md) |
| `/backend` build scaffolding (`buildSrc`, `gradle/`, `settings.gradle.kts`) | R-BA | R-DOA (CI integration) | [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1, [./DependencyManagement.md](./DependencyManagement.md) |

### 1.1 Stream → role mapping

The human team "streams" of [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §3 are team-topology groupings, not roles. In this operating system each stream resolves to the owning role(s) of the modules it names in §1:

| Stream (PhaseBasedImplementationPlan §3) | Owning role(s) per §1 |
|---|---|
| Platform/core (`eip-app`, `eip-core`, `eip-tenancy`, security, API conventions) | R-BA (co-owners: R-PA tenancy/quotas; R-SA security paths, CC-2) |
| Connectors + ingestion (`eip-connectors`, `eip-ingestion`, `/simulation`) | R-CNA (co-owners: R-DA normalization; R-QAA simulation-pack fitness) |
| Analytics (`eip-analytics`) | R-DA (co-owner: R-BA runtime/query side) |
| AI (`eip-ai`, `eip-reports` agent side) | R-AIA (R-BA is primary owner of `eip-reports`) |
| Frontend (`/frontend`) | R-FA |
| DevX/infra (`/infra`, CI, `/scripts`, observability stack) | R-DOA (R-SA for pipeline secrets) |

Where [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §16 requires "at least one review from the owning stream" on a PR, that requirement resolves to the G8 rule: R-CR review plus, where the change class demands it, the owning architect role(s) from this table (e.g., the CC-1 two-approval rule per [./QualityGatePolicy.md](./QualityGatePolicy.md)).

## 2. What ownership means

The owning role of a module/path MUST:

1. **Exercise G4 review rights.** Approve or reject CC-1/CC-4 changes touching the module (anchor-diff review). For CC-1 the approval chain is owner + R-CA; for CC-4 on schema, R-DBA is the gate owner. Co-owners approve within their scoped concern only.
2. **Maintain `MODULE.md`.** Keep the module charter current: purpose, owned tables/topics/endpoints, invariants, allowed dependencies (§3). A stale charter is the owner's G7 failure.
3. **Guard invariants.** Define the module's invariants in `MODULE.md` and block changes that violate them (examples: `eip-core` stays a leaf module; every `eip-tenancy` table is RLS-forced; `eip-analytics` exposes only `com.eip.analytics.query` to `eip-ai` — [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §3).
4. **Answer escalations.** Serve as the first escalation stop for R-IE/R-TE working in the module (default chain: engineer → owning domain architect → R-CA → human repository owner).
5. **Curate module debt.** Review DEBT entries touching the module each sprint ([./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md)); DEBT items older than 2 phases escalate to R-CA.

Co-ownership means: the co-owner's approval is required **only** when the diff touches their scoped concern (e.g., R-DA for a normalizer mapping change in `eip-ingestion`; R-PA for quota logic in `eip-tenancy`). The primary owner arbitrates overlap; disagreements between architects go to R-CA.

### 2.1 Ownership in the gate flow

| Gate | Owner's obligation |
|---|---|
| G0 Ready | Confirm the task's write-set is coherent for the module and does not collide with in-flight tasks (feeds R-TPM's DoR check) |
| G4 Architecture | Review CC-1/CC-4 anchor diffs touching the module within 1 working day; verdicts use the BLOCKER/MAJOR/MINOR/NIT scale |
| G5 Performance | For CC-3 diffs in owned hot paths, confirm the change respects the module's NFR budgets (e.g. API p95 < 300 ms — NFR-011; ingest 100k events/h — NFR-003, [../docs/product/PRD.md](../docs/product/PRD.md)) before R-PE's verdict |
| G6 Observability | Verify new endpoints/consumers/jobs in the module carry the `eip_*` metrics/traces/logs their MODULE.md promises |
| G7 Documentation | Confirm MODULE.md and the module's `/docs` sections reflect the merged change |
| RG1–RG4 | Attest at phase exit that the module's phase deliverables ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §17) are demonstrably complete |

## 3. MODULE.md charter contract

Every backend module and `/frontend` carries a `MODULE.md` created with the module (Phase 0/1) containing exactly these sections: **Purpose** (one paragraph) · **Owner** (role IDs, matching §1) · **Owned tables** (schema-qualified, per [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §2) · **Owned topics / consumer groups** (per [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md)) · **Owned endpoints** (per [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) catalog areas) · **Invariants** (numbered, testable) · **Dependencies** (allowed modules, mirroring the Modulith declaration). `MODULE.md` is L2 context ([./ContextManagementStrategy.md](./ContextManagementStrategy.md)) and appears in every task context pack for the module.

**Docs-lint rule — Owned tables:** every table in a `MODULE.md` "Owned tables" section MUST be a **subset of the rows DatabasePlan §2 assigns to that module** (DatabasePlan §2 is the single table→schema→owning-module catalog; exactly one owning module per table). Docs-lint fails (G7) on: a `MODULE.md` table with no matching DatabasePlan §2 row, a table DatabasePlan §2 assigns to a different module, or the same table claimed by two `MODULE.md` files. Infra-owned tables (`event_outbox`, `processed_events`, `worker_heartbeat`, `qrtz_*`) appear only in `eip-core`'s charter, annotated "infra-owned, accessed via core services only".

## 4. CODEOWNERS (Phase 0, ready to commit)

Each role maps to the team alias `@eip/r-<xx>` (lower-cased role ID). Team membership — which agent service accounts and humans hold each role — is administered by the human repository owner; R-CR reviews are enforced separately as a required review, since R-CR reviews every PR and is deliberately absent from CODEOWNERS. GitHub semantics: **last matching rule wins**, so specific paths follow general ones.

```
# CODEOWNERS — paths → owning engineering roles (see engineering-operating-system/ModuleOwnership.md)
# Last match wins. Every repository path MUST match at least one rule (codeowners-coverage CI check).

# --- Governance ---
/CLAUDE.md                          @eip/r-ca
/CODEOWNERS                         @eip/r-ca
/engineering-operating-system/      @eip/r-ca
/docs/adr/                          @eip/r-ca

# --- Documentation of record ---
/README.md                          @eip/r-de
/docs/                              @eip/r-de
/docs/architecture/generated/       @eip/r-ba

# --- Work state ---
/work/                              @eip/r-tpm

# --- Backend ---
/backend/                           @eip/r-ba
/backend/buildSrc/                  @eip/r-ba @eip/r-doa
/backend/gradle/                    @eip/r-ba @eip/r-doa
/backend/settings.gradle.kts        @eip/r-ba @eip/r-doa
/backend/eip-core/                  @eip/r-ba
/backend/eip-tenancy/               @eip/r-ba @eip/r-pa
/backend/eip-app/                   @eip/r-ba
/backend/eip-workers/               @eip/r-ba
/backend/eip-connectors/            @eip/r-cna
/backend/eip-ingestion/             @eip/r-cna @eip/r-da
/backend/eip-analytics/             @eip/r-da @eip/r-ba
/backend/eip-ai/                    @eip/r-aia
/backend/eip-reports/               @eip/r-ba @eip/r-aia
/backend/eip-app/src/main/resources/db/migration/  @eip/r-dba

# --- Frontend ---
/frontend/                          @eip/r-fa

# --- Infra, CI, tooling ---
/infra/                             @eip/r-doa
/.github/                           @eip/r-doa
/Makefile                           @eip/r-doa
/scripts/                           @eip/r-doa

# --- Simulation data packs ---
/simulation/                        @eip/r-cna @eip/r-qaa
```

Notes:

1. Multiple owners on one line = any listed owner's approval satisfies the code-owner requirement; the co-ownership scoping of §2 is enforced by review convention and R-CR verification, not by GitHub.
2. R-SA is intentionally not path-mapped: security review routes by change class (CC-2 → G3 full checklist + R-SA sign-off), because security-relevant diffs cross paths.
3. `CODEOWNERS` lives at the repository root per [./RepositoryStructure.md](./RepositoryStructure.md) §1.

## 5. Ownership transfer protocol

Ownership transfers (role reassignment, module split, load rebalancing) follow this sequence — no step may be skipped:

- [ ] 1. Initiator (current owner, incoming owner, or R-CA) opens a `docs/TASK-NNNN-ownership-transfer` PR.
- [ ] 2. The PR updates, atomically: `CODEOWNERS`, §1 of this document, and the module's `MODULE.md` Owner section.
- [ ] 3. Outgoing owner attaches a handoff note (`/work/handoffs/TASK-NNNN-1.md`) covering: current invariants and their tests, open DEBT/RISK entries for the module, in-flight tasks touching the module, known sharp edges.
- [ ] 4. Approvals required: outgoing owner + incoming owner + R-CA. For security-relevant modules (`eip-tenancy`, `eip-app` security paths) additionally R-SA.
- [ ] 5. R-TPM re-routes open tasks and pending G4 reviews to the new owner on merge; the transfer is effective at merge, never verbally.

An owner who becomes unavailable mid-sprint is substituted by their co-owner; if there is none, R-CA assumes ownership temporarily and records it in the sprint plan.

## 6. Orphaned-code rule

1. Every file in the repository MUST match at least one `CODEOWNERS` rule. The `codeowners-coverage` CI check fails any PR introducing an unmatched path ([./RepositoryRules.md](./RepositoryRules.md) §7).
2. A PR creating a new top-level directory or new Gradle module MUST, in the same PR: add the `CODEOWNERS` rule, add the §1 row, and (for modules) add `MODULE.md`. Structural invariant per [./RepositoryStructure.md](./RepositoryStructure.md) §6.
3. If ownership becomes ambiguous (owner role vacated, rule deleted by mistake), the path defaults to **R-CA** immediately; R-CA MUST reassign within one sprint, tracked as a DEBT entry in `/work/debt-register.md`.
4. Code whose owner cannot state its invariants is treated as orphaned even if a rule matches: R-CR flags it MAJOR, and a task to restore the `MODULE.md` charter is created before further feature work in that module.

## 7. Phase 0 bootstrap checklist

Ownership is live from the first commit, not retrofitted:

- [ ] Root `CODEOWNERS` committed exactly as §4 (first 10 PRs of [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md)).
- [ ] Team aliases `@eip/r-*` created and populated by the human repository owner before the first non-governance PR merges.
- [ ] Branch protection enables *require review from Code Owners* plus the required checks of [./BranchingStrategy.md](./BranchingStrategy.md) §7.
- [ ] `codeowners-coverage` check wired into CI (fails on unmatched paths).
- [ ] `MODULE.md` created for all nine backend modules and `/frontend` as their skeletons land, each with the §3 sections filled — no empty placeholders.
- [ ] §1 of this document verified against `CODEOWNERS` by docs-lint (role/path drift between the two is a G7 failure).

## Related documents

- [./AIAgentCatalog.md](./AIAgentCatalog.md) · [./AgentResponsibilities.md](./AgentResponsibilities.md) — the 21 engineering roles behind the aliases
- [./RepositoryRules.md](./RepositoryRules.md) — CODEOWNERS semantics and required checks
- [./RepositoryStructure.md](./RepositoryStructure.md) — the tree these rules cover
- [./QualityGatePolicy.md](./QualityGatePolicy.md) — G4 mechanics that ownership feeds
- [./ContextManagementStrategy.md](./ContextManagementStrategy.md) — MODULE.md as the L2 context layer
- [./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md) — module debt curation duties
- [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1/§3 — module boundaries the owners guard
