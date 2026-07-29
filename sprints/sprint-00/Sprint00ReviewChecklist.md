# SPRINT-00 Review Checklist

The review gates each SPRINT-00 task must clear at merge (per-task G-map) plus the sprint-level close-out review. Gate definitions: [QualityGatePolicy §2–§3](../../engineering-operating-system/QualityGatePolicy.md). Review verdicts BLOCKER/MAJOR/MINOR/NIT ([CodeReviewChecklist](../../engineering-operating-system/CodeReviewChecklist.md)). R-CR reviews with fresh context (diff + task spec + cited docs only).

## Per-task gate map

| Task | G0 | G1 | G2 | G3-auto | G4 | G7 | G8 | Notes |
|---|---|---|---|---|---|---|---|---|
| TASK-0001 | ✓ | ✓ | ✓ (trivial) | ✓ | — | ✓ | ✓ | No contract anchor; builds green |
| TASK-0002 | ✓ | ✓ | ✓ | ✓ | — | ✓ | ✓ | This task *is* CI; lint-block demo required |
| TASK-0003 | ✓ | ✓ | ✓ (smoke) | ✓ | — | ✓ | ✓ | NFR-050 boot-time evidence |
| TASK-0004 | ✓ | light | — | ✓ | — | ✓ | ✓ | docs-only |
| TASK-0005 | ✓ | ✓ | ✓ (≥85%) | ✓ | **✓ R-CA** | ✓ | **✓✓ two approvals** | CC-1; Modulith `verify()`; leaf-violation demo |
| TASK-0006 | ✓ | — | — | ✓ | **✓ R-CA** | ✓ | ✓ | ADR content approval |
| TASK-0007 | ✓ | — | — | ✓ | — | ✓ | ✓ | codeowners-coverage green |
| TASK-0008 | ✓ | ✓ | ✓ (self-test) | ✓ | — | ✓ | ✓ | four §L4 checks; block-then-pass demos |

**Not triggered this sprint:** G5 (no hot paths), G6 (no endpoints/consumers/jobs), G3 manual/CC-2 (no security-relevant surface), RG1–RG4 (release gates first apply at Phase-0 close, [Sprint03](../../program/Sprint03.md)). G3 runs its **automated** portion (gitleaks, dependency scan) on every PR.

## R-CR review checklist (every PR)

Applied by R-CR at G8, per [CodeReviewChecklist](../../engineering-operating-system/CodeReviewChecklist.md):

- [ ] **Change class declared and correct** (misclassification = BLOCKER). TASK-0005 must be CC-1; TASK-0006/0007 CC-6; others CC-7.
- [ ] **Task spec ACs met**, each with evidence (CI link or recorded demo) — no unverifiable "works" claims (unverifiable = BLOCKER).
- [ ] **Write-set matches the declared set** — no out-of-scope files touched (single-writer discipline).
- [ ] **Modulith/ArchUnit boundaries pass** (G1); for TASK-0005, `eip-core` proven a leaf.
- [ ] **No secrets / personal data** in code, fixtures, CI config (gitleaks green).
- [ ] **Docs landed in the same PR** where behavior/structure changed; docs-lint green (once TASK-0008 exists).
- [ ] **Standards conformance** — CodingStandards (records/sealed/constructor-injection for TASK-0005; editorconfig/license for TASK-0001).
- [ ] **CC-1 (TASK-0005): two approvals** (R-CR + R-CA); R-CA approach pre-approval note present from DoR.
- [ ] Commit messages Conventional-Commits with `[TASK-000N]`; branch name per BranchingStrategy.

## Sprint-level close-out review

At the SPRINT-00 review ([SprintReviewGuide](../../engineering-operating-system/SprintReviewGuide.md)):

- [ ] **Demo runs live** ([Sprint00.md §14](../../program/Sprint00.md)): clean clone → `make dev-up` healthy within NFR-050 → Modulith-violation PR blocked at G1 then fixed → browse `/docs/adr/ADR-001..020`.
- [ ] **Exit criteria met** ([Sprint00.md §9](../../program/Sprint00.md)): Compose ≤15 min/16 GB; CI enforces unit+integration+Modulith gates + coverage ratchet; eip-core ships base types + boundary test + MODULE.md; ADR-001..020 exist + docs-lint green; CODEOWNERS covers every path.
- [ ] **DoD audit** on a random 20% task sample (min 2) — R-QAA, deterministic seed; any FAIL becomes an escaped defect.
- [ ] **Readiness condition 4 satisfied**: ADRs + CODEOWNERS + docs-lint landed ([ImplementationReadinessDecision §3](../../reviews/architecture-readiness/ImplementationReadinessDecision.md)).
- [ ] **Metrics reviewed**: gate pass rate, escaped defects, coverage trend, PR cycle time, docs-lint violations, debt delta.
- [ ] **No individual-surveillance surface introduced** (NFR-071 — trivially true this sprint, but the check is standing).
- [ ] **Handoff to [Sprint01](../../program/Sprint01.md)** confirmed: eip-core skeleton + booted stack are the persistence/tenancy-spine inputs.

## Related documents

- [../../engineering-operating-system/QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md) · [../../engineering-operating-system/CodeReviewChecklist.md](../../engineering-operating-system/CodeReviewChecklist.md) · [../../engineering-operating-system/DefinitionOfDone.md](../../engineering-operating-system/DefinitionOfDone.md)
- [TaskSpecs.md](./TaskSpecs.md) · [Sprint00Plan.md](./Sprint00Plan.md) · [../../program/Sprint00.md](../../program/Sprint00.md)
