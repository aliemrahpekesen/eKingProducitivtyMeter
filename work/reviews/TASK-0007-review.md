# TASK-0007 — R-CR Review (with R-CA governance approval)

- **Task:** [../tasks/TASK-0007.md](../tasks/TASK-0007.md) · governance task · **change class CC-6** (`CODEOWNERS` change needs R-CA — [RepositoryRules §7.5](../../engineering-operating-system/RepositoryRules.md))
- **Branch:** `governance/TASK-0007-codeowners` · **base:** `integration/SPRINT-00` · **commit under review:** `f8cfd68`
- **Reviewers:** R-CR (independent) + **R-CA** (governance / CODEOWNERS-change approver) · **Date:** 2026-07-08
- **Method:** diff + task spec + cited docs only ([ModuleOwnership](../../engineering-operating-system/ModuleOwnership.md), [RepositoryRules §7](../../engineering-operating-system/RepositoryRules.md), [CodeReviewChecklist](../../engineering-operating-system/CodeReviewChecklist.md), [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md), [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md), [TaskSpecs TASK-0007](../../sprints/sprint-00/TaskSpecs.md)). Every claim independently re-derived from the diff via a last-match-wins simulator over `git ls-files` — the task file's numbers were not taken as evidence.

## Overall verdict: **APPROVED**

0 BLOCKER · 0 MAJOR · 1 MINOR · 1 NIT.

The CODEOWNERS correctly materializes ModuleOwnership §4 with complete coverage, valid syntax, and correct last-match-wins resolution for every critical path. The one MINOR is a docs-consistency follow-up (CODEOWNERS ↔ ModuleOwnership §1 drift) with a clear, non-blocking fix path.

## Acceptance criteria status

| AC | Status | Evidence |
|---|---|---|
| AC-1 — every top-level path owned (no unowned) | ✅ | Last-match-wins simulation over **249 tracked files → 0 unowned**; all 19 top-level paths owned. **0 files** rely on the `*` default (it is a pure orphan-backstop) |
| AC-2 — mapping matches ModuleOwnership §1/§4 | ✅ | All **28 §4 rows** reproduced with identical owners **and identical primary-owner-first order**; 27 §4/critical probes resolve correctly (0 mismatches) |
| AC-3 — valid CODEOWNERS syntax | ✅ | Every non-comment line is `pattern @owner[…]`; all rule-line aliases are known `@eip/r-*` roles; anchored patterns, dir `/` suffixes, exact-file rules, `*`, and multi-owner lines are valid GitHub syntax |

## R-CR review findings

**Independently verified:**

- **Scope:** commit `f8cfd68` adds **exactly** `.github/CODEOWNERS` + `work/tasks/TASK-0007.md`. No path outside the write-set; **no application source, no `/docs`, no EOS file** modified.
- **Coverage (AC-1):** 0 unowned across 249 files; the `*` default wins for **nothing** (every real path has a specific rule), so it only guards future stray files — exactly the [ModuleOwnership §6.1/§6.3](../../engineering-operating-system/ModuleOwnership.md) intent.
- **§4 fidelity (AC-2):** every §4 owner mapping present and correct, including the multi-owner order (`eip-analytics` → r-da r-ba; `eip-reports` → r-ba r-aia; `eip-tenancy` → r-ba r-pa; `eip-ingestion` → r-cna r-da; buildSrc/gradle/settings → r-ba r-doa; simulation → r-cna r-qaa).
- **Critical resolution (all correct under last-match-wins):** `.github/CODEOWNERS` → **R-CA** (not R-DOA), `.github/workflows/ci.yml` → **R-DOA**, `docs/adr/**` → **R-CA**, `docs/**` → **R-DE**, `docs/architecture/generated/**` → **R-BA**, `backend/eip-app/src/main/resources/db/migration/**` → **R-DBA**, `engineering-operating-system/**` → **R-CA**, `CLAUDE.md` → **R-CA**, `work/**` → **R-TPM**.
- **No overbroad ambiguity for critical files:** each critical file resolves to its specific scoped-owner set via the last (most specific) match; `*` never wins for a listed path.
- **Ordering correctness:** the author reordered general→specific per [RepositoryRules §7.3](../../engineering-operating-system/RepositoryRules.md) — this is **required and correct**. §4's *listed* order places `/docs/adr/` before `/docs/`, which under last-match-wins would have misassigned ADRs to R-DE; the reorder fixes that latent §4 rendering defect while preserving every owner mapping. Good catch, properly disclosed.
- **Security paths:** R-SA is intentionally not path-mapped ([ModuleOwnership §4 note 2](../../engineering-operating-system/ModuleOwnership.md)); security-sensitive files are owned by their module (e.g. `eip-tenancy` → r-ba r-pa) + CC-2 routing. No orphaned security path. Correct.

**MINOR-1 — CODEOWNERS ↔ ModuleOwnership §1 drift (docs-consistency; non-blocking).** The CODEOWNERS adds rules **not present in ModuleOwnership §1/§4**: `/program`, `/sprints`, `/reviews`, the loose root files (`.editorconfig`, `.gitignore`, `CONTRIBUTING.md`, `LICENSE`), the `*` default, and `/.github/CODEOWNERS`; and the file sits at `.github/` while §4 note 3 / [RepositoryRules §7.1](../../engineering-operating-system/RepositoryRules.md) say root. [ModuleOwnership §7](../../engineering-operating-system/ModuleOwnership.md) states docs-lint verifies §1 against CODEOWNERS and treats **role/path drift as a G7 failure**. So once TASK-0008 docs-lint activates, this drift fails G7. The additions are individually justified and disclosed, but the source-of-record (§1/§4) must be updated to match. *Fix:* a follow-up CC-6 R-CA doc PR updating ModuleOwnership §1/§4 (add the post-§4 rows + the `*` default + the `.github/` location) and RepositoryRules §7.1 / §7 location wording — **before** TASK-0008 lands. Non-blocking for this local merge (docs-lint not yet live). Recommend filing as **DEBT** at merge/close. The author correctly stayed within the declared write-set (`.github/CODEOWNERS` + task file) and disclosed every reconciliation, so this is a scoping-boundary follow-up, not an author defect.

**NIT-1.** The `*` default and the coverage-extension rows are sound (they satisfy §6.1 coverage where §4 alone would leave `/program`, `/sprints`, `/reviews`, and loose root files unowned). Folding them into the §1/§4 source (the MINOR-1 follow-up) restores CODEOWNERS to a pure materialization of the map.

## R-CA governance approval

**APPROVED.** The file faithfully implements the ownership map, the ordering obeys [RepositoryRules §7.3](../../engineering-operating-system/RepositoryRules.md), coverage satisfies the orphaned-code rule ([ModuleOwnership §6](../../engineering-operating-system/ModuleOwnership.md)), and no critical file has ambiguous or wrong ownership. The `CODEOWNERS`-change approval required by [RepositoryRules §7.5](../../engineering-operating-system/RepositoryRules.md) is granted. Condition: close MINOR-1 (the §1/§4 doc reconciliation) before TASK-0008 docs-lint activates.

## CODEOWNERS location decision (R-CA)

**Decision: approve `.github/CODEOWNERS` as the canonical location.**

Rationale: (1) the human repository owner explicitly directed this placement; (2) GitHub resolves `.github/CODEOWNERS` with identical semantics and precedence to a root file, and the intra-file path mappings are unaffected; (3) `.github/` is a conventional location that keeps the repo root uncluttered. The only consequence is wording: [ModuleOwnership §4 note 3](../../engineering-operating-system/ModuleOwnership.md), [RepositoryRules §7.1](../../engineering-operating-system/RepositoryRules.md), and the [§7](../../engineering-operating-system/ModuleOwnership.md) bootstrap checklist say "root" and must be updated to `.github/` — folded into the MINOR-1 reconciliation PR. Moving to root remains a valid one-line alternative if the owner later prefers zero divergence, but `.github/` is hereby ratified as canonical. This closes the location question delegated to R-CA.

## Evidence checked

- `git show --name-status f8cfd68` (2 files; no source/docs/EOS).
- CODEOWNERS syntax + alias validation (rule lines only).
- Last-match-wins simulator over `git ls-files`: 0 unowned; per-file winning-rule analysis (0 files rely on `*`).
- 27-probe §4/critical resolution table (0 mismatches); multi-owner order vs §4.

## Write-set discipline status

**Respected.** Only `.github/CODEOWNERS` + `work/tasks/TASK-0007.md`. Single-writer holds (L3 governance lane; no other in-flight task touches these). No application source or `/docs` baseline modified.

## DoD alignment (CC-6)

Docs/governance file authored + validated; Process (CC-6 declared; R-CA governance approval recorded here). Code/Tests/Security/Observability groups "n/a — governance metadata". **VERIFIED → DONE** and the *require-review-from-Code-Owners* / `codeowners-coverage` activation are pending push + branch protection (TASK-0002; no remote yet) — the standing SPRINT-00 deferral. MINOR-1 must close before TASK-0008 docs-lint.

## Required fixes

**None blocking.** MINOR-1 → file a DEBT entry and a follow-up CC-6 R-CA doc PR reconciling ModuleOwnership §1/§4 + RepositoryRules §7.1/§7 (add the post-§4 paths + `*` default; change "root" → `.github/`) before TASK-0008 docs-lint activates.

## Merge decision

**APPROVED FOR MERGE** into `integration/SPRINT-00` (local `--no-ff`), pending the user's explicit merge step. G8 R-CR approval + R-CA §7.5 approval satisfied; location ratified as `.github/`. Not merged/pushed in this review step, per instruction.

## Exact next recommended command

```
Merge TASK-0007 into SPRINT-00 integration only. Do not push. Do not create PR. Do not start TASK-0008.
Close the TASK-0007 review (R-CR + R-CA APPROVED — 0 BLOCKER/MAJOR, 1 MINOR, 1 NIT; location ratified as
.github/CODEOWNERS) and commit the review doc (docs: approve TASK-0007 review). Switch to
integration/SPRINT-00; merge governance/TASK-0007-codeowners with --no-ff; mark TASK-0007 MERGED in
work/sprints/SPRINT-00.md with a daily-log entry; file MINOR-1 as a DEBT entry in work/debt-register.md
(reconcile ModuleOwnership §1/§4 + RepositoryRules §7.1/§7 to add the post-§4 CODEOWNERS paths, the *
default, and the .github/ location — required before TASK-0008 docs-lint). Commit the integration-state
update (chore: merge TASK-0007 into sprint 00 integration). Report commit hashes, merge status, whether the
DEBT entry was filed, and the exact next recommended command (TASK-0008 docs-lint automation).
```
