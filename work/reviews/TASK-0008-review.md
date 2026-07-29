# TASK-0008 — R-CR Review

- **Task:** [../tasks/TASK-0008.md](../tasks/TASK-0008.md) · governance task (final Sprint-00 task) · **change class CC-7**
- **Branch:** `feature/TASK-0008-docs-lint` · **base:** `integration/SPRINT-00` · **commit under review:** `688f32b`
- **Reviewer:** R-CR (independent) · **Date:** 2026-07-08
- **Method:** diff + task spec + cited docs only ([DocumentationStandards §4](../../engineering-operating-system/DocumentationStandards.md), [QualityGatePolicy §2](../../engineering-operating-system/QualityGatePolicy.md), [TaskSpecs TASK-0008](../../sprints/sprint-00/TaskSpecs.md)). Every check independently re-run.

## Overall verdict: **APPROVED**

0 BLOCKER · 0 MAJOR · 3 MINOR · 1 NIT. All five ACs met: the four docs-lint check types are implemented, wired to CI as the G7 `docs-lint` stage, green over the current doc set, and block-then-pass demonstrated. The MINORs are coverage-completeness extensions (not defects) with documented future-tightening paths.

## Acceptance criteria status

| AC | Status | Evidence |
|---|---|---|
| AC-1 — runs as G7, green over baseline | ✅ | `python3 scripts/docs-lint/docs_lint.py` → **PASS, exit 0** over 139 files; `docs-lint` job added to `.github/workflows/ci.yml` (runs the script on every PR/push) |
| AC-2 — broken link blocked | ✅ | probe `[x](./missing.md)` → L1 FAIL, exit 1; removed → exit 0 (reproduced independently) |
| AC-3 — dangling `FR-999`/`ADR-099` blocked | ✅ | probe with both → L2 FAIL (both flagged), exit 1; removed → exit 0 (reproduced independently) |
| AC-4 — canonical drift blocked | ✅ | probe `current_setting('eip.tenant_id')` → L4 FAIL, exit 1; removed → exit 0 |
| AC-5 — all four L1–L4 checks implemented + wired | ✅ | L1 link+anchor, L2 id resolution, L3 FeatureCatalog count reconciliation, L4 canonical greps — all in `docs_lint.py`, wired to G7 |

## R-CR review findings

**Correctness (independently verified):**

- **Scope:** commit `688f32b` touches exactly `.github/workflows/ci.yml`, `scripts/docs-lint/docs_lint.py`, `scripts/docs-lint/README.md`, `work/tasks/TASK-0008.md` — all within the declared write-set. **No `/docs` product-spec content and no application source modified.**
- **Green over baseline:** re-ran the linter — PASS/exit 0 over 139 markdown files (L1/L2/L3/L4 all OK).
- **Block-then-pass:** reproduced (dangling-id probe → exit 1 → removed → exit 0, no probe left in tree). CI `docs-lint` job runs `python3 scripts/docs-lint/docs_lint.py`.
- **The context-aware L4 is the right call and a genuine defect-avoidance.** A naive `eip.tenant_id` grep would false-positive on the legitimate OTel span-attribute in `ObservabilityModel`/`SecurityChecklist`/`ObservabilityRequirements`; the implementation correctly forbids only the GUC expression (`current_setting`/`set_config`/`SET LOCAL`). Rule-defining docs (`DocumentationStandards`, `DocumentationQualityReview`, `TaskSpecs`) are exempted so the linter does not trip on its own documented examples. Good.
- **L3 reconciliation is sound:** verifies FeatureCatalog §1 Total (145) = area-column sum = P0+P1+P2 = §1.2 phase-sum = actual `FEAT-NNN` row count.
- **L2 correctly ignores 4-digit malformed ADR forms** (the `(?!\d)` boundary), so `DocumentationQualityReview`'s `ADR-0001` example is not misread.

**MINOR (non-blocking coverage extensions — recommend a single DEBT entry):**

- **MINOR-1 — `/work` out of docs-lint scope.** [DocumentationStandards §4](../../engineering-operating-system/DocumentationStandards.md) L1/L2 scope includes `/work` (+ TASK/SPRINT/DEBT/RISK resolution); this implementation scopes to the 5 TaskSpecs trees (`/docs`, `/eos`, `/reviews`, `/program`, `/sprints`) + MODULE.md + root docs, excluding `/work`. Justified (operational records reference the in-flight task/future sprints/example ids) and disclosed, but the source-of-record ultimately wants `/work` covered.
- **MINOR-2 — L4 canonical seed covers only the RLS-GUC value.** DocumentationStandards §4 L4 also names the DLQ pattern (`<group>.dlq`), NFR-figure drift, locale (`en-US`), and `pnpm`. The check is implemented and R-DE-extensible by design, but currently enforces only the GUC value (the one the AC-4 demo exercises). Extending needs context-aware patterns to avoid false positives (NFR figures legitimately in PRD, etc.).
- **MINOR-3 — L3 reconciles FeatureCatalog only.** DocumentationStandards §4 L3 also names Roadmap stated totals; Roadmap reconciliation is not implemented.

**NIT-1.** CI uses the runner's default `python3` (no `setup-python` pin). Fine for a standard-library-only script; pin if determinism ever matters.

## Evidence checked

- `git show --name-status 688f32b` (4 files; write-set + no-source verified).
- Independent linter runs (baseline green; dangling-id block-then-pass); `ci.yml` YAML parse (jobs: backend, frontend, security, **docs-lint**); the `docs-lint` job command.

## Write-set discipline status

**Respected.** Only `scripts/docs-lint/**`, `.github/workflows/ci.yml` (the TASK-0002-owned file, extended here by sequence per the SPRINT-00 conflict declaration — never concurrent), and `work/tasks/TASK-0008.md`. No product-spec or application source touched.

## DoD alignment (CC-7)

Code/tooling (linter + CI stage), Tests (block-then-pass self-demos), Docs (`scripts/docs-lint/README.md`; no `/docs` baseline change), Process (CC-7 declared). **VERIFIED → DONE** and the gate becoming merge-blocking activate once the integration branch is pushed and branch protection applies (TASK-0002; no remote yet) — the standing SPRINT-00 deferral.

## Required fixes

**None blocking.** MINOR-1/2/3 → file one DEBT entry capturing the docs-lint coverage extensions (/work scope + TASK/SPRINT/DEBT/RISK ids; additional L4 canonical values; Roadmap L3), to be paid when docs-lint goes remote-live and the doc set stabilizes.

## Merge decision

**APPROVED FOR MERGE** into `integration/SPRINT-00` (local `--no-ff`). This is the final Sprint-00 task; merging it completes the SPRINT-00 backlog. Not pushed. G8 R-CR approval granted.

## Exact next recommended command

```
Merge TASK-0008 into SPRINT-00 integration and close Sprint-00. Commit the review doc
(docs: approve TASK-0008 review); merge feature/TASK-0008-docs-lint --no-ff; mark TASK-0008 MERGED and
Sprint-00 COMPLETE in work/sprints/SPRINT-00.md; file MINOR-1/2/3 as DEBT-007 (docs-lint coverage
extensions); commit the integration-state (chore: merge TASK-0008 into sprint 00 integration; close
Sprint-00). Report Sprint-00 completion, the DEBT filed, and recommend the standing follow-up (push
integration/SPRINT-00 + apply branch protection to activate CI + docs-lint + CODEOWNERS on the remote).
```
