# Code Review — TASK-0002 CI pipeline + gate stages

- **Reviewer:** R-CR (independent, fresh-context) · **Gate:** G8 · **Date:** 2026-07-07
- **Task:** [../tasks/TASK-0002.md](../tasks/TASK-0002.md) · story P0-E1-S2 · change class CC-7
- **Branch:** `feature/TASK-0002-ci-pipeline` · **Base:** `integration/SPRINT-00` · **Commit:** `cc83cc7`
- **Checklists applied:** [CodeReviewChecklist.md](../../engineering-operating-system/CodeReviewChecklist.md) · [Sprint00ReviewChecklist.md](../../sprints/sprint-00/Sprint00ReviewChecklist.md)

## Overall verdict: **APPROVED**

Zero BLOCKERs, zero unwaived MAJORs → the approval rule ([CodeReviewChecklist §4](../../engineering-operating-system/CodeReviewChecklist.md)) is met. **3 MINOR + 4 NIT** advisory findings, none blocking. All four ACs and every CI stage were **independently re-run by the reviewer** (CodeReviewChecklist §1 — CI does not exist on the remote, so the reviewer executed the exact stage commands, not the author's prose). No deviation requires rework.

## Findings by severity

### BLOCKER — none
Every gate runs and fails correctly (independently verified); CC-7 matches the diff; no contract anchor, tenancy/security, or FR-057 surface; no unverifiable claim.

### MAJOR — none

### MINOR

- **[MINOR-1] Coverage config diverges from TestingStrategy §1.** `eip.java-conventions.gradle.kts` special-cases only `eip-core` at ≥85% and gives every other module 0.75, using JaCoCo's **default INSTRUCTION counter**. [TestingStrategy §1](../../docs/testing/TestingStrategy.md) is more specific: **≥85% line, ≥75% branch on `eip-core` *and* `eip-analytics`**; ≥75% line elsewhere. So (a) `eip-analytics` is under-tiered, and (b) the rule enforces INSTRUCTION coverage, not the LINE (≥85%) + BRANCH (≥75%) counters the spec names. No current impact (no code to measure), but it under-enforces once analytics and branch coverage matter (SPRINT-04+). *Fix now or file DEBT before SPRINT-04:* add `eip-analytics` to the ≥85% tier and express separate `LINE` and `BRANCH` limits.

- **[MINOR-2] The ESLint gate does not enforce `--max-warnings 0`.** `frontend/package.json`'s `lint` script is `eslint .`; [CodingStandards §1](../../engineering-operating-system/CodingStandards.md) mandates "ESLint with `--max-warnings 0`", and `eslint.config.js`'s own comment claims "runs with `--max-warnings 0` in CI." As written, warning-level rules (e.g. the config's `react-refresh/only-export-components: warn`) pass the gate. *Fix (trivial, recommended now):* `"lint": "eslint . --max-warnings 0"`.

- **[MINOR-3] Prettier check not wired.** [CodingStandards §1](../../engineering-operating-system/CodingStandards.md) lists "Prettier check + ESLint" for TS formatting/lint; only ESLint is present. The task spec's Static stage enumerated "ESLint/tsc" (Prettier omitted) and the frontend has near-zero code, so deferral to the frontend-shell task (P0-E5-S1, SPRINT-03) is defensible — but it should be **tracked** (DEBT entry or an explicit deferral note in the task), not silently dropped.

### NIT

- **[NIT-1] Workflow triggers on `main`.** The session base branch is `claude/engineering-productivity-platform-04kbet`, but `ci.yml` and the ruleset target `main` — correct per the EOS trunk convention ([RepositoryRules](../../engineering-operating-system/RepositoryRules.md)/BranchingStrategy name `main`). Whoever pushes must ensure the repo's trunk/default is `main`, or the triggers and ruleset won't apply. Push-time note.
- **[NIT-2] NullAway uses `AnnotatedPackages=com.eip`** rather than the JSpecify `@NullMarked` package-info convention ([CodingStandards §2.1](../../engineering-operating-system/CodingStandards.md)). Equivalent enforcement for now; the `@NullMarked` `package-info.java` files arrive with real code (TASK-0005+).
- **[NIT-3] `gitleaks … --exit-code 1` is redundant** (`detect` already exits 1 on findings). Harmless.
- **[NIT-4] Commit subject omits the `[TASK-0002]` trailer + `type(scope)` scope** (`chore: implement TASK-0002 CI pipeline gates`). User-mandated verbatim; the ID is in the subject (traceable). Squash-merge message should carry the full `chore(infra): … [TASK-0002]` form. Consistent with the TASK-0001 NIT.

## Evidence checked

| Area | How verified | Result |
|---|---|---|
| Diff scope | `git diff --name-status` base..branch | 12 files, all inside the declared write-set (`.github/`, `backend/buildSrc/**`, `backend/config/checkstyle/`, `backend/gradle/libs.versions.toml`, `frontend/**`, `work/tasks/TASK-0002.md`); no product-spec change |
| Convention plugins | Read committed `eip.java-conventions`, `eip.modulith-conventions`, `buildSrc/build.gradle.kts` | ErrorProne + NullAway(ERROR), Checkstyle(maxWarnings 0), JaCoCo verify wired into `check`, ArchUnit test dep present |
| Workflow | Read committed `ci.yml`; `yaml.safe_load` | 3 jobs (backend/frontend/security); JDK 21 Temurin; pnpm pinned via corepack + `--frozen-lockfile`; gitleaks license-free; dependency-review PR-only; read-only permissions; concurrency cancel |
| Ruleset | Read + `json.load` | Required checks `backend`/`frontend`/`security` match job names exactly; PR-only, 1 approval, code-owner, no force-push/deletion |
| Checkstyle / ESLint configs | Read committed | Checkstyle lean/non-formatting (no Spotless overlap); ESLint flat config, `no-explicit-any: error` |
| Artifacts / secrets | `git ls-files` grep; gitleaks premise | No build outputs; `pnpm-lock.yaml` committed (RepositoryRules); no secrets in diff |
| PR size | net lines excl. lockfile | ~390 net (1395 − ~1005 lockfile) — within the ≤400 target (CodeReviewChecklist §J) |

## Acceptance criteria status (independently re-run)

| AC | Reviewer verification | Status |
|---|---|---|
| AC-1 CI runs Static+Unit+Integration+Modulith/ArchUnit, green on empty modules | `ci.yml` defines the stages; `./gradlew build check` → **exit 0**; static subset (`spotlessCheck checkstyleMain checkstyleTest compileJava compileTestJava`) → **exit 0**; frontend `install --frozen-lockfile && lint && typecheck && build` → **exit 0** | ✅ PASS |
| AC-2 Spotless/Checkstyle violation blocked, then passes | Spotless violation → `spotlessCheck` **exit 1**, revert → **exit 0**; unused import → `checkstyleMain` **exit 1** (UnusedImports), revert → **exit 0** — reviewer-run | ✅ PASS |
| AC-3 Coverage ratchet active, fails below threshold | `jacocoTestCoverageVerification` wired into `check`, **exit 0** on empty (skips, no measured classes); thresholds present. *Caveat: MINOR-1 — thresholds diverge from TestingStrategy §1 (analytics tier + LINE/BRANCH).* | ✅ PASS (with MINOR-1) |
| AC-4 `main` protected: PR + checks + approval | Ruleset JSON valid; required checks match job names; PR/approval/code-owner/no-force-push encoded. *Application requires a push (deviation 1).* | ✅ PASS (config-as-code) |

## Validation command status (reviewer-run)

| Command | Result |
|---|---|
| `yaml.safe_load(ci.yml)` / `json.load(ruleset)` | valid; jobs & required checks = backend/frontend/security |
| `cd backend && ./gradlew build check` | **exit 0** |
| `cd backend && ./gradlew jacocoTestCoverageVerification` | **exit 0** |
| frontend `install --frozen-lockfile && lint && typecheck && build` | **exit 0** |
| Spotless block-then-pass | **exit 1 → 0** |
| Checkstyle block-then-pass | **exit 1 → 0** |

CI cannot run on the GitHub remote without a push (out of scope). Per [CodeReviewChecklist §1](../../engineering-operating-system/CodeReviewChecklist.md) the reviewer **re-ran** the exact stage commands the workflow invokes; the G1/G2 substance is confirmed green on this commit. The workflow itself runs on `main` once pushed (task reaches VERIFIED then).

## Change-class, write-set, CI-stage correctness, toolchain, pnpm

- **CC-7 correct.** Build/infra tooling; no contract anchor, schema, or security-relevant application surface. No secrets added (gitleaks is license-free download; no tokens in the workflow).
- **Write-set: clean.** All 12 files within the declared set; `buildSrc`/`frontend` edits are sequential over TASK-0001 (MERGED), not concurrent. No out-of-scope files; no product-spec change.
- **CI stage correctness.** Stage→gate mapping matches [CodingStandards §1](../../engineering-operating-system/CodingStandards.md) and [TestingStrategy §16](../../docs/testing/TestingStrategy.md): Spotless/Checkstyle/ErrorProne/NullAway/Modulith = G1, JUnit+JaCoCo = G2, ESLint/tsc = G1, gitleaks+dependency-review = G3-auto. `./gradlew check` is the correct single Modulith/ArchUnit entry point (activates ModularityTests when TASK-0005 adds them).
- **Java 21 / Gradle compatibility.** CI uses `actions/setup-java` Temurin 21, so `./gradlew` (wrapper 8.14.3) runs on JDK 21 — no Java-25-runtime issue in CI; the daemon-JVM pin from TASK-0001 is redundant-but-harmless there.
- **pnpm pinning honored.** `corepack prepare pnpm@9.15.9 --activate` + `pnpm install --frozen-lockfile` — the pin is enforced and the lockfile is frozen. Committed `pnpm-lock.yaml` updated for the new ESLint deps.

## Deviations assessment (the five documented in the task file)

| # | Deviation | Reviewer assessment | Rework? |
|---|---|---|---|
| 1 | Branch protection not applied (needs push + admin) | **Correct** — recorded as config-as-code; ruleset JSON valid, required-check contexts match job names exactly; apply command documented in `.github/README.md`. AC-4 met as the reviewable source of truth. | No |
| 2 | Integration stage green-empty (no Testcontainers tests yet) | **Correct** — `./gradlew check` runs integration tests via the `check` lifecycle the moment they exist (SPRINT-01+). Nothing to run now. | No |
| 3 | Modulith/ArchUnit rules → TASK-0005 | **Correct** — tooling wired (ArchUnit `testImplementation` verified; `check` entry point); the boundary `ModularityTests` are TASK-0005's CC-1 deliverable. | No |
| 4 | DEBT-001 partially touched (versions in catalog; buildSrc mirrors by hand) | **Correct** — catalog updated for traceability; buildSrc duplication remains, tracked by the unchanged DEBT-001. | No |
| 5 | CI not executed on GitHub (no push) | **Correct** — validated by re-running every stage command locally; reviewer independently confirmed. | No |

**Conclusion: no deviation requires rework.** All five are correct scoping or necessary constraints, each documented.

## Required fixes

**None blocking.** Recommended: **MINOR-2** (`--max-warnings 0` — trivial, fix now; it is the very gate this task establishes). File DEBT for **MINOR-1** (coverage config vs TestingStrategy §1 — no impact until SPRINT-04) and **MINOR-3** (Prettier deferral). Honor NIT-4 in the squash-merge message; action NIT-1 at push time.

## Merge decision

**TASK-0002 may be merged locally into `integration/SPRINT-00`.** Approval rule met (0 BLOCKER, 0 unwaived MAJOR). G8 verdict APPROVED. The workflow's gates formally activate on push to `main` (and once the ruleset is applied), at which point the task moves MERGED → VERIFIED → DONE; until then the reviewer's re-run is the interim G1/G2 evidence. Recommend applying MINOR-2 and filing the two DEBT entries (MINOR-1, MINOR-3) before closing the task to DONE.

## Exact next recommended command

```
Apply the trivial MINOR-2 fix (set frontend lint script to "eslint . --max-warnings 0") and file DEBT-003 (coverage config vs TestingStrategy §1: add eip-analytics to the ≥85% tier + LINE/BRANCH counters, MINOR-1) and DEBT-004 (Prettier check not wired, deferred to the frontend-shell task P0-E5-S1, MINOR-3) in work/debt-register.md. Then merge feature/TASK-0002-ci-pipeline into integration/SPRINT-00 locally (no push, no PR), mark TASK-0002 MERGED in work/sprints/SPRINT-00.md, and begin TASK-0003 (Compose dev stack, P0-E1-S3) reading only its context pack from sprints/sprint-00/TaskSpecs.md#task-0003 and program/ContextManifest.md "SPRINT-00". Do not push.
```

---

*Review artifact per [AgentCommunicationProtocol](../../engineering-operating-system/AgentCommunicationProtocol.md). R-CR did not edit source (CodeReviewChecklist §5); the block-then-pass demos were reverted, working tree clean. This file is uncommitted — the review checklists do not explicitly require committing the review document.*
