# SPRINT-00 Definition of Ready Check

R-TPM's G0 verdict for every SPRINT-00 task, applying the [DefinitionOfReady](../../engineering-operating-system/DefinitionOfReady.md) checklist. A task moves INTAKE→READY only when every applicable item passes ([QualityGatePolicy §4](../../engineering-operating-system/QualityGatePolicy.md): G0 has no waiver). This file records the check; at claim time it is copied into each `/work/tasks/TASK-000N.md` `DoR check` field.

## G0 checklist columns

Abbreviations: **Story** (P-E-S cited & in phase) · **FR/AC** (refs resolve — see note) · **CC** (change class declared, consistent with write-set) · **Prob** (problem statement decision-free) · **AC-test** (acceptance criteria testable) · **Test** (test plan / validation commands present) · **Obs** (observability plan or "none — justified") · **Docs** (docs impact declared) · **OOS** (out-of-scope stated) · **WS** (write-set declared & disjoint) · **Role** (assignee matches ModuleOwnership) · **Ctx** (context pack complete & session-sized) · **Dep** (deps DONE or stubbed) · **Size** (S or M).

| Task | Story | FR/AC | CC | Prob | AC-test | Test | Obs | Docs | OOS | WS | Role | Ctx | Dep | Size | **G0** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| TASK-0001 | ✓ P0-E1-S1 | ✓ (see note) | ✓ CC-7 | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-IE/R-BA | ✓ | ✓ (root) | ✓ S | **PASS** |
| TASK-0002 | ✓ P0-E1-S2 | ✓ | ✓ CC-7 | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-IE/R-DOA | ✓ | ✓ 0001 | ✓ M | **PASS** |
| TASK-0003 | ✓ P0-E1-S3 | ✓ NFR-050 | ✓ CC-7 | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-IE/R-DOA | ✓ | ✓ 0001 | ✓ M | **PASS** |
| TASK-0004 | ✓ P0-E1-S4 | ✓ | ✓ CC-6 | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-DE | ✓ | ✓ 0003 | ✓ S | **PASS** |
| TASK-0005 | ✓ P0-E2-S1 | ✓ | ✓ **CC-1** | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-IE/R-BA/R-CA | ✓ | ✓ 0001 | ✓ M | **PASS\*** |
| TASK-0006 | gov (cond-4) | ✓ | ✓ CC-6 | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-CA/R-DE | ✓ | ✓ 0001 | ✓ M | **PASS** |
| TASK-0007 | gov | ✓ | ✓ CC-6 | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-CA | ✓ | ✓ 0001 | ✓ S | **PASS** |
| TASK-0008 | gov | ✓ | ✓ CC-7 | ✓ | ✓ | ✓ | none✓ | ✓ | ✓ | ✓ | ✓ R-DE/R-DOA | ✓ | ✓ 0002 | ✓ M | **PASS** |

\* **TASK-0005 (CC-1) special-type DoR:** per [DefinitionOfReady §2](../../engineering-operating-system/DefinitionOfReady.md), a CC-1 task also needs R-CA **approach pre-approval** recorded before READY→CLAIMED. The direction (skeleton base types strictly following DomainModel §1–§2, eip-core stays a leaf, no persistence) is sound and pre-approvable; the approval note is added to `/work/tasks/TASK-0005.md` before claim. No ADR is needed (skeleton records no *new* decision — it implements ADR-014/AD-14 already Accepted).

## Notes on FR/AC references for foundation tasks

SPRINT-00 tasks are platform-foundation and governance work; several map to **NFR/phase-exit criteria and PRD §7 constraints** rather than feature FR/AC IDs — this is expected for Phase-0 scaffolding and is DoR-satisfying because each cites a resolvable, in-phase requirement:

| Task | Requirement anchor (resolves in `/docs` or readiness) |
|---|---|
| TASK-0001 | PhaseImpl §5.1 P0-E1-S1; RepositoryStructure (EOS); PRD §7 constraint 3 (fixed stack) |
| TASK-0002 | PRD §10 Phase-0 exit line 1 (CI enforces gates); NFR-061 (Modulith boundaries CI-verified) |
| TASK-0003 | **NFR-050** (Compose ≤15 min/16 GB); PRD §10 Phase-0 exit line 4 |
| TASK-0004 | PhaseImpl §5.1 P0-E1-S4 |
| TASK-0005 | FR-031 (event envelope), FR-034/FR-035 (canonical vocabulary/WorkItem supertype); ADR-014/AD-14 (identity) |
| TASK-0006 | ImplementationReadinessDecision §3 condition 4; ADRProcess §4 |
| TASK-0007 | NFR-061 / ModuleOwnership (path ownership) |
| TASK-0008 | PRD §7.6 (docs-as-code merge gate); DocumentationStandards §L4 |

R-TPM verifies each anchor resolves and is in-phase; the "FR/AC present" item is satisfied by these in-phase requirement citations for foundation tasks (the checklist requires a resolvable in-phase requirement, and NFR/exit-criteria/constraint IDs qualify).

## Verdict

**All 8 tasks: G0 PASS → READY.** TASK-0005 pending its CC-1 approach pre-approval note (added before CLAIMED). Recorded by: R-TPM, 2026-07-07.

## Related documents

- [../../engineering-operating-system/DefinitionOfReady.md](../../engineering-operating-system/DefinitionOfReady.md) · [../../engineering-operating-system/QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md)
- [TaskSpecs.md](./TaskSpecs.md) · [Sprint00Backlog.md](./Sprint00Backlog.md)
