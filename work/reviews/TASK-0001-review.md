# Code Review — TASK-0001 Monorepo scaffolding

- **Reviewer:** R-CR (independent, fresh-context) · **Gate:** G8 · **Date:** 2026-07-07
- **Task:** [../tasks/TASK-0001.md](../tasks/TASK-0001.md) · story P0-E1-S1 · change class CC-7
- **Branch:** `feature/TASK-0001-monorepo-scaffolding` · **Commit:** `6a0e0a3`
- **Checklists applied:** [CodeReviewChecklist.md](../../engineering-operating-system/CodeReviewChecklist.md) · [Sprint00ReviewChecklist.md](../../sprints/sprint-00/Sprint00ReviewChecklist.md)

## Overall verdict: **APPROVED**

Zero BLOCKERs, zero unwaived MAJORs → the approval rule ([CodeReviewChecklist §4](../../engineering-operating-system/CodeReviewChecklist.md)) is satisfied. Two MINOR and three NIT advisory findings, none blocking. All four acceptance criteria were **independently re-run by the reviewer** (not accepted on the author's prose, per CodeReviewChecklist §1). No deviation requires rework.

## Findings by severity

### BLOCKER — none
No never-waivable gate substance, contract anchor, tenancy/security invariant, regression-test rule, or change-class violation. No unverifiable claim (every AC re-run below).

### MAJOR — none

### MINOR

- **[MINOR-1] Version catalog is not the single source it claims to be.** `backend/gradle/libs.versions.toml` is commented "the single source of truth for backend dependency versions," but the actual versions are duplicated as string literals in `backend/buildSrc/build.gradle.kts` (spring-boot `3.4.1`, dependency-management `1.1.7`, spotless `6.25.0`), the foojay resolver `0.8.0` is hardcoded in `settings.gradle.kts`, and module build files use string coordinates (`"org.springframework.boot:spring-boot-starter"`) rather than catalog accessors. Several catalog entries (`spring-modulith-*`, the spotless/foojay plugins) are unreferenced. A bump in the catalog would not propagate to `buildSrc` → version-drift risk; [RepositoryStructure §2](../../engineering-operating-system/RepositoryStructure.md) states "Version catalogs pin all dependency versions." *Fix now or file DEBT:* wire module dependencies through the catalog and reconcile the `buildSrc` versions (buildSrc cannot consume the project catalog directly — acknowledge that with a shared `gradle.properties` or narrow the comment). Natural point: TASK-0005, which adds the Spring Modulith dependencies.

- **[MINOR-2] `gradle/verification-metadata.xml` (dependency checksum verification) absent.** [RepositoryStructure §2](../../engineering-operating-system/RepositoryStructure.md) lists it in the backend build scaffolding (a DependencyManagement §10 supply-chain control). It is reasonable to defer until the dependency set stabilizes — and DependencyManagement is outside TASK-0001's required-docs set — but it is part of the canonical layout and should be tracked. *File DEBT* to add it once dependencies stabilize, or fold it into TASK-0002's supply-chain wiring.

### NIT

- **[NIT-1] Commit message omits the `[TASK-0001]` trailer and a `type(scope)` scope.** `chore: implement TASK-0001 monorepo scaffolding` references the task in the subject (traceability intact) but not in the canonical `type(scope): summary [TASK-NNNN]` form ([CodingStandards §J](../../engineering-operating-system/CodingStandards.md)). The message was user-mandated verbatim. Because PRs squash-merge, the final `main` commit should carry the full form (e.g. `chore(repo): scaffold monorepo tree [TASK-0001]`). Author's discretion.

- **[NIT-2] Placeholder UI string in `frontend/src/app/App.tsx`** (`"Engineering Intelligence Platform — scaffold"`). [CodingStandards §4](../../engineering-operating-system/CodingStandards.md) makes hard-coded UI strings a MAJOR at G8 — but the i18n framework (react-i18next) is an explicit later deliverable (P0-E5-S1, SPRINT-03), there are no i18n keys to externalize into yet, and the string sits in a placeholder component the same story replaces wholesale (`App.tsx` comment: "Replaced by the real application shell in P0-E5-S1"). Not inflated to MAJOR because the enforcement mechanism does not yet exist and the file is transitional. **Binding note:** the §4 rule becomes enforceable the moment i18n scaffolding lands; any hard-coded string surviving into a real feature is a MAJOR then.

- **[NIT-3] `eip.modulith-conventions` and `eip.dependency-rules` are currently identical to `eip.java-conventions`.** Both only apply `java-conventions`. Intentional, documented forward-hooks (they diverge in TASK-0005 / TASK-0002). Acceptable.

## Evidence checked

| Area | How verified | Result |
|---|---|---|
| Diff scope | `git diff --name-status` base..branch | 60 files: 59 additions + 1 `README.md` modification (declared in write-set) |
| Backend build config | Read `settings.gradle.kts`, `build.gradle.kts`, `libs.versions.toml`, `gradle-daemon-jvm.properties`, wrapper props, all four `buildSrc` convention plugins | Clean, well-commented, faithful to BackendPlan §1 / CodingStandards §2/§8 |
| Module dependency edges | Read all nine `eip-*/build.gradle.kts`; extracted `project(...)` edges | **Match BackendPlan §1 exactly** (see table below) |
| eip-app stub | Read `EipApplication.java` | `@SpringBootApplication` stub only, license header present, no field injection, no business logic |
| Frontend | Read `package.json`, `tsconfig.json`, `vite.config.ts`, `main.tsx`, `App.tsx`, `index.html` | strict TS, no `any`/`@ts-ignore`, no non-null assertion (`main.tsx` uses explicit null check), `lang="en-US"`, pnpm pinned |
| README change | `git diff -- README.md` | Surgical, accurate, non-destructive status update + build pointer |
| Artifacts | `git ls-files \| grep -E 'node_modules\|/dist/\|/build/\|.gradle/\|.jar'` | Only the required `gradle-wrapper.jar`; no build outputs committed |
| Secrets / PII | Grep of diff | None (`.gitignore` excludes `.env*`/keys/pems) |

Dependency-edge verification (reviewer-extracted vs BackendPlan §1 "May depend on"):

| Module | Declared edges | BackendPlan §1 | ✓ |
|---|---|---|---|
| eip-core | (none) | (none — leaf) | ✓ |
| eip-tenancy | core | eip-core | ✓ |
| eip-connectors | core, tenancy | eip-core, eip-tenancy | ✓ |
| eip-ingestion | core, tenancy, connectors | eip-core, eip-tenancy, eip-connectors | ✓ |
| eip-analytics | core, tenancy | eip-core, eip-tenancy | ✓ |
| eip-ai | core, tenancy, analytics | eip-core, eip-tenancy, eip-analytics | ✓ |
| eip-reports | core, tenancy, analytics, ai | eip-core, eip-tenancy, eip-analytics, eip-ai | ✓ |
| eip-app | all 7 library modules | All modules (composition root) | ✓ |
| eip-workers | ingestion, ai, reports | All modules (composition root; §1 mermaid WRK→ING/AI/REP) | ✓ |

## Acceptance criteria status (independently re-run)

| AC | Reviewer verification | Status |
|---|---|---|
| AC-1 `./gradlew build` on the empty module set | `cd backend && ./gradlew clean build` → **exit 0**; 49 tasks, `eip-app` bootJar built, Spotless green, Java 21 toolchain provisioned | ✅ PASS |
| AC-2 `pnpm --dir frontend build` | `rm -rf frontend/dist && corepack pnpm build` → **exit 0**; strict `tsc --noEmit` clean, `vite build` emits `dist/` | ✅ PASS |
| AC-3 tree matches RepositoryStructure path-by-path; pnpm pinned | Tree matches §1–§4 for in-scope paths (backend under `/backend` per §2; `.github`/`CODEOWNERS` correctly absent — TASK-0002/0007); `packageManager: pnpm@9.15.9` + committed `pnpm-lock.yaml` (v9.0) | ✅ PASS |
| AC-4 exactly nine `eip-*` modules | `./gradlew moduleList` + `settings.gradle.kts` grep = 9 exact names, no extra/missing | ✅ PASS |

## Validation command status

| Command | Reviewer result |
|---|---|
| `cd backend && ./gradlew build` | **exit 0** (re-run twice, incl. `clean build`) |
| `pnpm --dir frontend build` | **exit 0** (clean rebuild, `dist/` produced) |
| `./gradlew moduleList` | 9 modules: ai, analytics, app, connectors, core, ingestion, reports, tenancy, workers |

CI does not exist yet (TASK-0002). Per [CodeReviewChecklist §1](../../engineering-operating-system/CodeReviewChecklist.md), the reviewer **re-ran** the validation rather than accept prose — so the G1/G2 *substance* (compile, Spotless, no tests to fail) is confirmed green on this commit. The machine-gate G1/G2/G3-auto/G7 *stages* attach when TASK-0002 lands and re-verify on `main` (task reaches VERIFIED then).

## Change-class, write-set, and hygiene

- **CC-7 correct.** The diff touches no contract anchor (no `/api/v1`, EventModel, DomainModel, SecurityModel, or SPI surface), no schema/Flyway, no security-relevant code. Scaffolding + build wiring = CC-7. `eip-core` here is a *build skeleton* only (no domain content) — the CC-1 shared-kernel content is TASK-0005; classifying this task CC-1 would be wrong. **No misclassification.**
- **Write-set discipline: clean.** All changes fall inside the declared TASK-0001 write-set (`/backend/**`, `/frontend/**`, `/infra`, `/scripts`, `/simulation`, `/work`, root config, README). `Makefile` and `README.md` are declared shared/modified files, sequenced with TASK-0003/TASK-0004. No out-of-write-set edits; no product-spec files changed except the declared README note.
- **Branch/commit hygiene.** Branch name per [BranchingStrategy](../../engineering-operating-system/BranchingStrategy.md); single commit; body detailed with `Co-Authored-By`. Subject convention → NIT-1.
- **No forbidden documents used.** The diff and task file cite only the SPRINT-00 allowed set (RepositoryStructure, BackendPlan §1, CodingStandards, RepositoryRules, BranchingStrategy, ArchitectureOverview §5); no AI/RAG/MCP/connector/DB-DDL/Security-deep/K8s content appears anywhere in the diff.
- **No business/domain logic.** Confirmed: nine modules have no sources except `eip-app`'s `@SpringBootApplication` stub (the one allowed stub); frontend is a placeholder shell. Empty `src` package roots only.

## Deviations assessment (the five documented in the task file)

| # | Deviation | Reviewer assessment | Rework? |
|---|---|---|---|
| 1 | Gradle build root under `/backend` (not repo root) | **Correct** — [RepositoryStructure §2](../../engineering-operating-system/RepositoryStructure.md) is the source of record and AC-3 binds to it; the TaskSpecs file-list was the imprecise side. Docs-first upheld. | No |
| 2 | Error Prone/NullAway/Checkstyle/ArchUnit deferred to TASK-0002 (G1 static stage) | **Correct task boundary** — those are G1 CI enforcement, and TASK-0001's "Out of scope" names CI as TASK-0002. Hooks exist in `buildSrc`. TASK-0002 MUST deliver them. | No |
| 3 | Modulith `ModularityTests` harness → TASK-0005 | **Correct** — TASK-0005 (eip-core, CC-1) owns the module-boundary test; `eip.modulith-conventions` is the present hook. | No |
| 4 | `eip-workers` is a library skeleton (no boot main / boot-app-conventions yet) | **Correct and necessary** — the Spring Boot `bootJar` requires a `mainClass`; applying boot now with no worker main would fail the build. Task scope limited the app-class stub to `eip-app`. Documented in its MODULE.md; worker main lands SPRINT-05. | No |
| 5 | `gradle-daemon-jvm.properties` pins the daemon to Java 21 (local box defaults to Java 25, which Gradle 8.14.3 rejects as its runtime) | **Sound portability fix** — makes `./gradlew build` run regardless of launcher default; CI on Java 21 needs no override. The feature is "incubating" in Gradle 8.14 (functional; low risk). | No |

**Conclusion: no deviation requires rework.** All five are correct scoping or necessary technical decisions, each documented.

## Required fixes

**None blocking.** Recommended (non-blocking): address **MINOR-1** (wire/reconcile the version catalog) and **MINOR-2** (add `verification-metadata.xml`) — either now or as DEBT entries folded into TASK-0005 / TASK-0002 respectively. NIT-1 (commit convention) should be honored in the squash-merge message.

## Merge decision

**TASK-0001 may be merged locally into the sprint integration branch.** Approval rule met (0 BLOCKER, 0 unwaived MAJOR). The G8 verdict is APPROVED; the machine gates (G1/G2/G3-auto/G7) formally attach once TASK-0002 provides CI, at which point the task moves MERGED → VERIFIED → DONE. Until then the reviewer's re-run stands as the interim G1/G2 evidence the task's DoD note anticipated. Recommend filing the two MINORs as DEBT before closing the task to DONE.

## Readiness for TASK-0002 dependency usage

**Ready.** TASK-0002 (CI pipeline) depends on TASK-0001 for the module tree, and the scaffold delivers everything it needs: a working `./gradlew build`, the four `buildSrc` convention plugins the CI stages invoke, JaCoCo already applied (coverage-ratchet hookable at G2), Spotless wired (`spotlessCheck` ready for the static stage), and the daemon/toolchain pin so a Java-21 CI runner runs `./gradlew build` directly. **Forward note for TASK-0002:** it owns wiring the deferred static analysis (deviation 2), and the Modulith stage will be green-but-empty until TASK-0005 adds `ModularityTests` — TASK-0002 should point the boundary stage at `./gradlew check` so those tests activate automatically when they land.

## Exact next recommended command

```
Merge TASK-0001 locally into the SPRINT-00 integration branch, then begin TASK-0002 (CI pipeline, P0-E1-S2). First: create the sprint integration branch if absent and fast-forward/merge feature/TASK-0001-monorepo-scaffolding into it locally (no push, no PR); mark TASK-0001 MERGED in work/sprints/SPRINT-00.md and file DEBT entries for MINOR-1 (version-catalog wiring) and MINOR-2 (verification-metadata.xml) in work/debt-register.md. Then read only the TASK-0002 context pack from sprints/sprint-00/TaskSpecs.md#task-0002 and program/ContextManifest.md "SPRINT-00", and implement the CI pipeline — wiring the deferred Error Prone/NullAway/Checkstyle/ArchUnit static analysis and pointing the Modulith stage at ./gradlew check. Do not start TASK-0003.
```

---

*Review artifact per [AgentCommunicationProtocol](../../engineering-operating-system/AgentCommunicationProtocol.md). R-CR did not edit source (CodeReviewChecklist §5). This file is uncommitted — the review checklists do not explicitly require committing the review document, per the task's conditional-commit instruction.*
