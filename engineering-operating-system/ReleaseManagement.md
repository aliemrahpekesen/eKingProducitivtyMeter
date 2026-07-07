# Release Management

This document defines how EIP releases are planned, gated, cut, distributed, patched, and rolled back. It is read by R-RM (who owns every process in this document), by R-TPM when planning the final sprints of a phase, by domain architects and R-SA/R-PE when delivering their release-gate verdicts, and by every engineering agent whose task lands in the last sprint before a release train departs. It governs the *engineering* release process; it does not describe the product's 18 runtime agents (see `../docs/ai/AgentArchitecture.md` — those are product features, not release actors).

## 1. Release trains

Releases are phase-aligned trains, not date-aligned. One minor version per phase, per [Roadmap §1 and §10](../docs/product/Roadmap.md):

| Train | Phase | Headline | Exit criteria source |
|---|---|---|---|
| v0.1 | Phase 0 | Foundations | [PRD §10 — Phase 0](../docs/product/PRD.md) |
| v0.2 | Phase 1 | Ingestion core + first connectors | PRD §10 — Phase 1 |
| v0.3 | Phase 2 | Analytics & dashboards | PRD §10 — Phase 2 |
| v0.4 | Phase 3 | AI core | PRD §10 — Phase 3 |
| v0.5 | Phase 4 | Full agent suite + MCP + outputs | PRD §10 — Phase 4 |
| v1.0 | Phase 5 | Enterprise hardening | PRD §10 — Phase 5 |

Rules:

- A train departs (version is tagged) only when RG1–RG4 all pass. A phase MUST NOT exit on schedule pressure; the train waits (Roadmap §2: phases are exit-criteria-gated, not date-gated).
- Patch releases `v0.N.P` MAY ship between trains from the `release/v0.N` branch (§6). **No feature backports across the train** ([Roadmap §10](../docs/product/Roadmap.md)); patches carry `fix`, security, and docs commits only.
- From v0.3 onward every release MUST be demoable end-to-end from the Docker Compose stack and simulation packs, air-gapped (Roadmap §10).
- Frontend and backend always release on the same train with the same version (see [VersioningStrategy §7](./VersioningStrategy.md)).

## 2. Roles and authority

| Role | Release authority |
|---|---|
| R-RM | Owns the train: freeze, branch cut, RG1–RG4 orchestration, go/no-go verdict (A4), tag, distribution, rollback decision |
| R-SA | A4 blocking verdict on RG2; override only by the human repository owner, recorded (escalation chain per [AgentResponsibilities.md](./AgentResponsibilities.md)) |
| R-PE | A4 blocking verdict on RG3 |
| R-PO | Accepts RG1 feature evidence against ACs; owns scope disputes at the gate |
| R-DOA | Produces release artifacts (images, offline bundle, signatures) |
| R-DE | Verifies docs/runbook currency for RG4 (G7 discipline at release scale) |
| Human owner | Mandatory L4 checkpoint at every release per [AIValidationWorkflow.md](./AIValidationWorkflow.md); final authority on any gate override |

## 3. Release gates RG1–RG4

Each gate produces a written verdict (PASS / FAIL / PASS-WITH-WAIVER) recorded in the release record (§8). A waiver on any RG item MUST name the accepting authority per [RiskManagementPolicy §5](./RiskManagementPolicy.md) and file a DEBT or RISK entry.

**RG1 — Phase exit criteria (verdict: R-PO evidence + R-RM):**

- [ ] Every checklist item in the phase's [PRD §10](../docs/product/PRD.md) section demonstrated with CI evidence links
- [ ] All phase-scoped acceptance criteria green in CI (`../docs/product/AcceptanceCriteria.md`)
- [ ] Demo milestone performed live from the Compose stack (air-gapped from Phase 3 onward, Roadmap §2)
- [ ] No open critical defect against phase scope; no task for phase scope left short of DONE without a recorded R-PO scope decision
- [ ] Risk register reviewed: no un-mitigated score ≥ 15 risk targeting this phase ([RiskManagementPolicy §4](./RiskManagementPolicy.md))

**RG2 — Security certification (verdict: R-SA, A4):**

- [ ] SecurityChecklist.md executed against release scope; all G3 automated scans green on the release commit
- [ ] Cross-tenant isolation sweep covers 100% of shipped endpoints with zero leaks (NFR-041)
- [ ] NFR-071 anti-surveillance review passed: no individual-ranking surface anywhere (FR-057 — release-blocking, no waiver possible below the human owner)
- [ ] Dependency/CVE scan report generated and bundled for air-gapped operators ([OperationsGuide §12](../docs/operations/OperationsGuide.md), CVE watch row)

**RG3 — Performance at phase scale (verdict: R-PE, A4):**

- [ ] Phase-relevant NFR budgets demonstrated on reference hardware: NFR-003 (100k events/h + 3× burst) from Phase 1; NFR-010/NFR-011 latencies from Phase 2; NFR-013 AI latencies from Phase 3; NFR-001–004 full scale at v1.0 (PRD §6)
- [ ] No unexplained regression vs. the previous train's recorded baselines
- [ ] RISK-010 caveat: until PRD §11 OQ#8 (reference hardware) is resolved, R-PE MUST state on the verdict which hardware the numbers were produced on

**RG4 — Operability (verdict: R-RM with R-DOA/R-DE evidence):**

- [ ] Upgrade path tested: previous train → this release, following [OperationsGuide §7](../docs/operations/OperationsGuide.md) verbatim, including its verification checklist and a rollback rehearsal (step 6)
- [ ] Backup/restore drill relevant to the phase executed per [OperationsGuide §6.2–6.3](../docs/operations/OperationsGuide.md) and recorded (from Phase 5, the HA failover drill additionally per §6.3)
- [ ] Release notes complete (§5) including Flyway migration list, event `schemaVersion` changes, and connector compatibility notes — OperationsGuide §7 step 1 depends on them
- [ ] `/docs` reflects what shipped (Roadmap §2 item 5); runbooks current; docs-lint green
- [ ] Offline bundle built, checksummed, signed, and smoke-installed on a clean air-gapped host (§5.2)

## 4. Release checklist (train departure sequence)

R-RM executes, in order:

- [ ] **T-2 sprints:** announce the freeze plan in the sprint plan; R-TPM stops scheduling CC-1/CC-4 tasks that cannot reach DONE before freeze
- [ ] **Freeze:** tag the freeze candidate on `main`; `release/v0.N` is cut lazily from that tag per [BranchingStrategy.md](./BranchingStrategy.md) §6 — pre-GA (v0.1–v0.4) only when a patch to the shipped release is needed, standing from the Phase 5 GA cadence; `main` reopens immediately for next-phase work
- [ ] Run RG1–RG4 (§3) against the release branch head; collect written verdicts
- [ ] Generate release notes (§5.1) and have R-DE lint them
- [ ] R-DOA builds versioned images and the offline bundle (§5.2)
- [ ] Hold the go/no-go meeting; record it (§8); obtain the human-owner L4 sign-off
- [ ] Tag `vX.Y.Z` on the release branch head; tags are immutable — a re-cut is a new patch version, never a moved tag
- [ ] Publish artifacts + checksums + signatures; verify `sha256sum -c` on a copy downloaded from the distribution point
- [ ] Smoke-install the published bundle once more from the distribution point (not the build directory) on a clean host
- [ ] File post-release TASKs for every PASS-WITH-WAIVER item; update the risk and debt registers
- [ ] Close the release record (§8) and announce the release with links to notes, bundle, and upgrade procedure ([OperationsGuide §7](../docs/operations/OperationsGuide.md))

## 5. Release notes and artifacts

### 5.1 Release notes

Release notes MUST be generated from Conventional Commit history (`type(scope): summary [TASK-NNNN]`, [BranchingStrategy §5](./BranchingStrategy.md)) between the previous tag and the release head — never hand-written from memory. Required sections, in order: **Highlights** (per phase headline) · **Breaking/upgrade-relevant changes** (Flyway migrations applied, event `schemaVersion` bumps with upcaster notes per [EventModel §5](../docs/engineering/EventModel.md), new required config, connector compatibility notes, SPI deprecations per [ConnectorFramework §2.1](../docs/engineering/ConnectorFramework.md)) · **Features** (feat commits grouped by module scope, each linking its TASK) · **Fixes** · **Security** (CVE fixes; coordinate disclosure wording with R-SA) · **Known issues** (open MAJOR waivers, relevant RISK IDs). Commits without a `[TASK-NNNN]` reference MUST be resolved to a task before notes are finalized (they indicate a RepositoryRules violation — file it).

### 5.2 Air-gap distribution artifacts

Every release ships the offline bundle exactly as specified in [DockerCompose §11](../docs/infrastructure/DockerCompose.md): `eip-offline-<version>.tar.gz` containing `images.tar` (every pinned image, including optional-profile images and Ollama model blobs), the `infra/docker-compose/` tree, release notes, and `sha256sums.txt` **signed**. Additional MUSTs:

- Checksums cover every file in the bundle; the signature covers `sha256sums.txt`; the signing key is held by the human owner and R-RM only.
- The bundle embeds the Trivy + dependency scan reports for its images (OperationsGuide §12 requires operators to review them before upgrading).
- Image tags follow [VersioningStrategy §8](./VersioningStrategy.md); no `latest` tag exists anywhere in a release.
- The bundle MUST be smoke-installed on a host with no network access (RG4) — NFR-051 is verified on the artifact, not the source tree.

## 6. Hotfix and patch releases

1. A defect eligible for a patch is: a critical or high defect in the released train, a security fix, or a docs correction operators depend on. Features are never eligible (Roadmap §10).
2. The fix lands on `main` first as a normal task (regression test before fix, [DevelopmentLifecycle §7](./DevelopmentLifecycle.md)), then is cherry-picked to `release/v0.N` by commit SHA with `[TASK-NNNN]` preserved. Cherry-pick order MUST follow the original commit order. Only when the fix cannot apply to `main` (code removed by next-phase work) MAY it be authored directly on `release/v0.N`, with an explicit R-RM-approved note in the PR and a tracking TASK confirming `main` is unaffected.
3. Patch PRs target `release/v0.N`, run the full G1–G3, G7, G8 gate set, plus conditional gates for their change class; R-SA verdict is mandatory for security patches (CC-2).
4. R-RM tags `v0.N.P`, regenerates release notes for the delta, rebuilds and re-signs the offline bundle. A patch that includes a Flyway migration or an event schema MINOR bump MUST re-run the RG4 upgrade test (previous patch → new patch).
5. `release/v0.N` branches are retired when the next train tags; afterwards, fixes ship only on the newest train (single supported line pre-1.0; v1.0 commits to the upgrade path from any v0.x ≥ 0.3 per Roadmap §10).

## 7. Rollback protocol

Rollback decision authority is **R-RM alone** (A4); for suspected security or tenant-isolation causes R-SA can force the decision (fail closed, OperationsGuide §9 T6). The protocol relies on compatibility rules other policies enforce — rollback is designed in advance, not improvised:

| Precondition | Enforced by |
|---|---|
| DB schema is N-1 compatible: migrations are expand–contract, backward-compatible for one minor version | CC-4 gate G4 (R-DBA), [VersioningStrategy §6](./VersioningStrategy.md), [OperationsGuide §7 step 3](../docs/operations/OperationsGuide.md) |
| Event consumers tolerate the previous train's producers: MINOR-additive schemas + upcasters; unknown MAJOR routes to DLQ instead of corrupting state | [EventModel §5](../docs/engineering/EventModel.md), FR-038 |
| Previous images remain available, immutably tagged | VersioningStrategy §8 |

Procedure (per OperationsGuide §7 step 6): redeploy previous images — workers first, then app replicas, then frontend. If a migration must be undone, restore PostgreSQL from the pre-upgrade backup and re-sync the delta from connectors; **never hand-edit `flyway_schema_history`**. After any rollback R-RM MUST: file an S1/S2 incident record, add a RISK entry for the root cause, and block the re-release until the failed gate that let the defect through is analyzed in the next sprint review.

## 8. Go/no-go meeting record

The go/no-go record lives at `/work/releases/RELEASE-vX.Y.Z.md` (this file family extends the `/work` layout of [RepositoryStructure §4](./RepositoryStructure.md); R-RM owns the directory). Template fields — all mandatory:

```
version · date · train (phase) · release branch + commit SHA
RG1 verdict + evidence links (R-PO)      RG2 verdict (R-SA)
RG3 verdict + hardware statement (R-PE)  RG4 verdict + drill records (R-RM)
waivers: [gate item · justification · accepting authority · DEBT/RISK id]
open risks ≥ 15 consulted (RISK ids)     rollback readiness confirmed (yes + evidence)
go/no-go decision · decider (R-RM) · human-owner sign-off · date
post-release TASKs filed
```

A NO-GO record MUST state the concrete exit condition for the next attempt. The record is L1-adjacent durable truth: it is never edited after the decision; corrections are appended.

## Related documents

- [VersioningStrategy.md](./VersioningStrategy.md) — version schemes this document's trains, tags, and artifacts use
- [BranchingStrategy.md](./BranchingStrategy.md) — `release/v0.N` branch mechanics and protection
- [QualityGatePolicy.md](./QualityGatePolicy.md) — merge gates G0–G8 feeding the release gates
- [RiskManagementPolicy.md](./RiskManagementPolicy.md) — waiver/acceptance authority, risk input to RG1
- [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md) — DEBT entries for release waivers
- [AIValidationWorkflow.md](./AIValidationWorkflow.md) — L4 human checkpoint at releases
- [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §1, §2, §10 — trains, phase gates, release policy
- [../docs/product/PRD.md](../docs/product/PRD.md) §10 — phase release criteria (RG1 source of record)
- [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) §6–§7 — backup/restore drills and upgrade/rollback procedure
- [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md) §11 — offline bundle contents
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §5 — event schema compatibility underpinning rollback
