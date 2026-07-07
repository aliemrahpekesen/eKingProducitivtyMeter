# Code Review — TASK-0004 Developer docs / onboarding

- **Reviewer:** R-CR (independent, fresh-context) · **Gate:** G8 · **Date:** 2026-07-07
- **Task:** [../tasks/TASK-0004.md](../tasks/TASK-0004.md) · story P0-E1-S4 · change class CC-6 (docs)
- **Branch:** `docs/TASK-0004-developer-docs` · **Base:** `integration/SPRINT-00` · **Commit:** `158fa7d`
- **Checklists applied:** [CodeReviewChecklist.md](../../engineering-operating-system/CodeReviewChecklist.md) · [Sprint00ReviewChecklist.md](../../sprints/sprint-00/Sprint00ReviewChecklist.md) · [DocumentationStandards.md](../../engineering-operating-system/DocumentationStandards.md) (§2/§5/§8)

## Overall verdict: **APPROVED**

Zero BLOCKERs, zero unwaived MAJORs → the approval rule ([CodeReviewChecklist §4](../../engineering-operating-system/CodeReviewChecklist.md)) is met. **1 MINOR + 3 NIT**, none blocking. All acceptance criteria independently verified. A clean, accurate, well-structured onboarding guide that reflects the current Phase-0 repo state.

## Findings by severity

### BLOCKER — none

### MAJOR — none
The only DocumentationStandards §2 tension (the URL/credential table, MINOR-1) does not rise to MAJOR: MAJOR is reserved for "restating with drift found" ([DocumentationStandards §2](../../engineering-operating-system/DocumentationStandards.md)), and the reviewer confirmed **zero drift** (token-identical to `make dev-urls`), with the table explicitly prefaced "Printed by `make dev-urls`".

### MINOR

- **[MINOR-1] `CONTRIBUTING.md §4` restates ports/URLs/dev-credentials whose authoritative home is `make dev-urls`.** [DocumentationStandards §2/§8](../../engineering-operating-system/DocumentationStandards.md) ("reference, never restate"; "no new copy of a port") targets exactly this. Mitigations are strong — the table is drift-free today (reviewer-verified identical to the Makefile), and it points at `make dev-urls` as the source — and an onboarding guide legitimately wants the URLs visible. But the table will silently drift if the compose ports/creds change and this doc isn't updated in the same PR. *Recommended (non-blocking):* trim §4 to the key entry points with "run `make dev-urls` for the authoritative live list," or add a standing note that `make dev-urls` wins on any mismatch. The §2 rule is primarily aimed at cross-spec drift; this is the mildest form of it.

### NIT

- **[NIT-1] Dev-credential strings will trip gitleaks when CI goes live.** `eip_dev_pw`/`admin_dev_pw`/`eip_minio_dev_pw` now appear in `CONTRIBUTING.md` (as well as the Makefile and `.env.example`). When the TASK-0002 CI runs on push, the `security` job's gitleaks (G3) may flag these password-shaped literals. They are intentional non-production dev placeholders (tracked by **DEBT-005** for the `CHANGE_ME` convergence). A gitleaks allowlist entry (or resolving DEBT-005) will be needed so G3 doesn't red on documented dev placeholders — a forward note for when CI activates, not a fix for this PR.
- **[NIT-2] `README.md` status prose restates "nine backend Gradle modules"** (a count whose home is [BackendPlan §1](../../docs/engineering/BackendPlan.md)). Descriptive index prose in a README is acceptable; noted only for completeness against §2.
- **[NIT-3] Commit subject** `docs: implement TASK-0004 developer onboarding` lacks the `(docs)` scope + `[TASK-0004]` trailer ([DocumentationStandards §5](../../engineering-operating-system/DocumentationStandards.md)/BranchingStrategy). User-mandated message pattern; ID traceable. Honor the full form in the squash-merge message (consistent with prior tasks).

## Evidence checked

| Area | How verified | Result |
|---|---|---|
| Diff scope | `git diff --name-status` base..branch | 3 files: `CONTRIBUTING.md` (new), `README.md` (status + Getting-started), `work/tasks/TASK-0004.md` (new). No `/docs` spec file, no code |
| Relative links | script over both docs | **All resolve**; in-page anchors (`#7-troubleshooting`, …) all valid |
| Placeholders | grep TBD/TODO/FIXME/WIP/coming-soon/lorem | **clean** |
| `make` targets | grep Makefile for each documented target | all 8 exist (dev-up/down/stop/ps/urls, backend-build, frontend-build, help) |
| URLs/ports/creds vs `make dev-urls` | token-set diff CONTRIBUTING vs Makefile | **IDENTICAL** (zero drift) |
| Build/validation commands | cross-check | `./gradlew build check` + frontend `lint`/`typecheck`/`build` scripts exist; match the CI jobs |
| `/docs` baseline | diff | **untouched** |
| Secrets / artifacts | diff | none (dev placeholders are intentional/documented, DEBT-005) |
| Structure (§5) | read | one H1; purpose paragraph (who/when); 9 ordered sections; "Related documents" at end; GFM tables; en-US |
| Content-vs-reality (§8) | read + prior session verification | accurate — "backend/frontend run on host", known-limitations correctly list what does **not** exist yet (app containers, CI-on-remote); no doc claims unbuilt behavior |

## Acceptance criteria status (independently verified)

| AC | Reviewer verification | Status |
|---|---|---|
| AC-1 clone → booted stack → PR without a human | `CONTRIBUTING.md` §1→§6 is a complete, ordered path (prerequisites → `make dev-up` → `make dev-urls`/build → branch/gates/PR); every step is a command verified working this session | ✅ PASS |
| AC-2 matches TASK-0003 exactly | make targets and the §3/§4 URLs/ports/dev-creds are **token-identical** to `make dev-urls`/the Makefile | ✅ PASS |
| AC-3 links + gates + no placeholders | all links resolve; contribution rules link QualityGatePolicy, BranchingStrategy, DefinitionOfReady/Done, RepositoryRules, and the CLAUDE.md ritual; zero placeholders | ✅ PASS |

## Documentation quality assessment

High. The guide is genuinely followable end-to-end (clone → prerequisites → `make dev-up` → `make dev-urls` → build/validate → branch/PR), scoped correctly (host-run app vs Compose infra), and honest about Phase-0 limitations (host port overrides, no app containers, minimal Keycloak realm, CI-not-yet-on-remote). It conforms to [DocumentationStandards §5](../../engineering-operating-system/DocumentationStandards.md) structure/style (one H1, purpose paragraph, ordered sections, tables, relative links, en-US, no placeholders, "Related documents"), triggers no [§3 update-matrix](../../engineering-operating-system/DocumentationStandards.md) row (it is not an endpoint/event/metric/schema change), and correctly leaves the `/docs` spec baseline untouched by living at root `CONTRIBUTING.md`. The single substantive critique is the §2 restatement of the URL/credential table (MINOR-1), which is drift-free and source-pointed today.

## Required fixes

**None blocking.** Optional: address **MINOR-1** (trim §4 to a pointer, or accept with a drift-guard note) now or defer; plan the gitleaks allowlist for the documented dev placeholders (**NIT-1**) as part of the DEBT-005 / CI-activation work; honor **NIT-3** in the squash-merge message.

## Merge decision

**APPROVED for merge** into `integration/SPRINT-00`. The docs match the built reality, all ACs pass, and no gate substance is missing. With TASK-0004 merged, SPRINT-00 Epic-1 (P0-E1-S1..S4) plus P0-E2-S1 are complete; the governance-backfill tasks (TASK-0005 eip-core skeleton, TASK-0006 ADRs, TASK-0007 CODEOWNERS, TASK-0008 docs-lint) remain to close the sprint. (Docs-lint L1–L4 becomes an automated G7 gate when TASK-0008 lands; this PR was verified against those checks manually.)

## Exact next recommended command

```
Merge TASK-0004 into SPRINT-00 integration, then begin TASK-0005 (eip-core domain skeleton, P0-E2-S1 — the first CC-1 task, needs R-CA approach pre-approval before CLAIMED). First: commit this review (git add work/reviews/TASK-0004-review.md && git commit -m "docs: approve TASK-0004 review"), then git switch integration/SPRINT-00 && git merge --no-ff docs/TASK-0004-developer-docs, mark TASK-0004 MERGED in work/sprints/SPRINT-00.md, and commit "chore: merge TASK-0004 into sprint 00 integration". Optionally apply MINOR-1 (trim CONTRIBUTING §4 to a make dev-urls pointer) on the feature branch before merging. Do not push. Do not create a PR.
```

---

*Review artifact per [AgentCommunicationProtocol](../../engineering-operating-system/AgentCommunicationProtocol.md). R-CR did not edit source (CodeReviewChecklist §5). This file is uncommitted — the review checklists do not explicitly require committing the review document.*
