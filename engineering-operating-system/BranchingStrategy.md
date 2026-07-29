# Branching Strategy

This document defines the trunk-based branching model of the EIP monorepo: branch types and naming, lifetime limits, PR size discipline, release and hotfix flows, merge strategy, and the required status checks per branch type. Every engineering agent follows it mechanically — two agents starting the same task MUST produce identically named branches. Repository-wide rules (protected `main`, generated files, write-sets) are in [./RepositoryRules.md](./RepositoryRules.md); release trains and go/no-go in [./ReleaseManagement.md](./ReleaseManagement.md); version numbering in [./VersioningStrategy.md](./VersioningStrategy.md).

## 1. Model

1. **Trunk-based development.** `main` is the only long-lived integration branch. It MUST be releasable at all times: every merge passes G1–G3 and the automated portion of G7.
2. All work happens on short-lived branches cut from `main` (or from `release/v0.N` for hotfixes, §6) and lands via squash-merged PR.
3. There is no `develop`, no personal long-running branch, no merge queue bypass. Feature flags and expand–contract migrations ([../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §7) are the tools for incomplete work on trunk — not long-lived branches.

## 2. Branch types and naming

| Type | Pattern | Cut from | Merges into | Used for |
|---|---|---|---|---|
| Feature | `feature/TASK-NNNN-slug` | `main` | `main` | New functionality per a task spec |
| Fix | `fix/TASK-NNNN-slug` | `main` (or `release/v0.N`, §6) | same base | Bug fixes; MUST contain the failing regression test in a commit before the fix commit |
| Refactor | `refactor/TASK-NNNN-slug` | `main` | `main` | Registered behavior-preserving refactors per [./RefactoringPolicy.md](./RefactoringPolicy.md) |
| Docs | `docs/TASK-NNNN-slug` | `main` | `main` | CC-6 docs-only changes (/docs, EOS docs, MODULE.md) |
| ADR | `adr/TASK-NNNN-slug` | `main` | `main` | New or superseding `ADR-NNN` files, usually paired with the doc diff they justify |
| Release | `release/v0.N` (`release/v1.0` for Phase 5) | `main` at the phase-exit tag — cut **lazily**: pre-GA (v0.1–v0.4) only when a patch to the shipped release is needed; standing from the Phase 5 GA cadence | — (tag source) | Stabilization and patch line for a shipped phase version |

Rules:

1. `slug` is 2–5 kebab-case words derived from the task title. Branch names contain exactly one `TASK-NNNN`; a branch without a task reference MUST NOT be pushed.
2. One task ⇢ normally one branch ⇢ one PR. If a task splits into multiple PRs, suffix the slug (`-part1`, `-part2`) and declare the ordering in the task spec.
3. Branches are deleted on merge. Nobody rebases or force-pushes another agent's branch (single-writer rule, [./RepositoryRules.md](./RepositoryRules.md) §2).

Examples:

| Verdict | Branch name | Why |
|---|---|---|
| Valid | `feature/TASK-0087-jira-webhook-intake` | type + task + concise kebab slug |
| Valid | `fix/TASK-0155-dora-window-off-by-one` | fix with task ref; regression test lands first |
| Valid | `adr/TASK-0203-qdrant-default-eval` | ADR branch tied to a decision task |
| Invalid | `feature/jira-webhooks` | missing `TASK-NNNN` — untraceable |
| Invalid | `emrah/wip-stuff` | personal namespace, no type, no task |
| Invalid | `feature/TASK-0087` | no slug — unreadable in PR lists |

## 3. Lifetime limits

1. Work branches live ≤ **5 working days** from first push to merge. R-TPM flags older branches at daily status.
2. A branch exceeding the limit MUST be either (a) split per §4 and partially landed, or (b) closed with a handoff note (`/work/handoffs/TASK-NNNN-<seq>.md`) and the task returned to READY.
3. Rebase onto `main` at least daily while a branch is open; resolve drift on the branch, never by merging `main` repeatedly into it (squash hides mess, but reviewers should not have to).

## 4. PR size rule and split guidance

1. Target: ≤ **~400 net lines** changed, excluding generated and lock files ([./RepositoryRules.md](./RepositoryRules.md) §4). Larger PRs require a declared justification in the PR description; R-CR MAY still demand a split (MAJOR verdict).
2. Task sizing prevents oversized PRs upstream: an L-sized task MUST be split before CLAIMED ([./DefinitionOfReady.md](./DefinitionOfReady.md)).
3. Split along these seams, in order of preference:
   - **Docs/ADR first** — land the contract or decision change (CC-1/CC-6) before the implementation (docs-first rule).
   - **Expand before contract** — CC-4 schema changes: expand migration + dual-read code in PR 1; backfill and contract migration in a later PR at least one release apart ([../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §7).
   - **Module boundaries** — one module's write-set per PR; cross-module features land consumer-safe producer changes first (additive events, additive API per [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md)).
   - **Tests-with-code, never tests-later** — splitting MUST NOT produce a test-free PR; each part carries its own tests (G2 applies to every part).

## 5. Merge strategy and commit conventions

1. **Squash merge only** on every PR. The branch's intermediate commits are working notes; the squash commit is the permanent record.
2. Squash commit message = Conventional Commit: `type(scope): summary [TASK-NNNN]`.
   - Types: `feat` `fix` `refactor` `test` `docs` `build` `chore` `perf`.
   - Scope = module name: `eip-core`, `eip-tenancy`, `eip-connectors`, `eip-ingestion`, `eip-analytics`, `eip-ai`, `eip-reports`, `eip-app`, `eip-workers`, `frontend`, `infra`, `docs`, `eos`.
   - Example: `feat(eip-connectors): add SonarQube incremental sync checkpointing [TASK-0117]`.
3. Bug-fix PRs show red→green evidence: the failing regression test committed **before** the fix commit, visible in CI history or the PR description (review protocol in [./CodeReviewChecklist.md](./CodeReviewChecklist.md)).
4. Release-branch cherry-picks use `git cherry-pick -x` so the origin commit is traceable.

## 6. Release branches and hotfix flow

Platform versions map to phases: v0.1 (Phase 0) → v0.2 → v0.3 → v0.4 → v0.5 → v1.0 (Phase 5), patches `v0.N.P`, tags `vX.Y.Z` ([./VersioningStrategy.md](./VersioningStrategy.md); phase exit criteria = [../docs/product/PRD.md](../docs/product/PRD.md) §10).

1. **Cut:** when release gates RG1–RG4 pass, R-RM creates the tag `v0.N.0` on the phase-exit commit on `main`. `release/v0.N` is cut lazily from that tag — pre-GA (v0.1–v0.4) only when a patch to the shipped release is needed; from the Phase 5 GA cadence, standing release branches are normal practice ([./ReleaseManagement.md](./ReleaseManagement.md) §4).
2. **Stabilization:** only `fix/` and `docs/` PRs may target a release branch. No features, no refactors, no dependency upgrades except security patches ([./DependencyManagement.md](./DependencyManagement.md) §7).
3. **Hotfix flow (trunk-first):**
   - If the bug exists on `main`: fix on `fix/TASK-NNNN-slug` from `main`, merge to `main`, then cherry-pick (`-x`) to `release/v0.N` via a second PR. R-RM approves the release-branch PR; tag `v0.N.P`.
   - If the bug exists **only** on the release branch: branch from `release/v0.N`, merge there first, tag `v0.N.P`, then forward-port to `main` within **2 working days** (same TASK) or record in the task file why no forward-port is needed.
   - Both PRs cite the same `TASK-NNNN`; the release PR description states the target tag and rollback note.
4. Release branches are never merged back wholesale; they end at end-of-support for their version and are then locked.

## 7. Required status checks per branch type

| PR target / class | G1 build & static | G2 tests | G3 security (automated) | G7 docs-lint | Reviews required |
|---|---|---|---|---|---|
| `main` — CC-7 standard | MUST | MUST | MUST | MUST | R-CR (G8) |
| `main` — CC-1 contract anchor | MUST | MUST | MUST | MUST | R-CR + owning architect + R-CA (G4) |
| `main` — CC-2 security-relevant | MUST | MUST | MUST + full [./SecurityChecklist.md](./SecurityChecklist.md) | MUST | R-CR + R-SA |
| `main` — CC-3 hot-path | MUST | MUST | MUST | MUST | R-CR + R-PE evidence (G5) |
| `main` — CC-4 schema/migration | MUST | MUST (incl. RLS tests) | MUST | MUST | R-CR + R-DBA (G4) |
| `main` — CC-5 AI-behavior | MUST | MUST + AI eval suite ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md)) | MUST | MUST | R-CR + R-AIA |
| `main` — CC-6 docs-only | skipped-as-success via path filter | skipped-as-success | secret scan only | MUST | light G8 (R-CR) |
| `release/v0.N` — any | MUST | MUST | MUST | MUST | R-CR + R-RM |

G1 includes compile, Spotless/Error Prone/NullAway, ESLint/`tsc`, Modulith/ArchUnit boundary verification, and the OpenAPI diff check; G2 enforces the coverage ratchet (thresholds per [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §1/§16 — ratchets may rise, never fall, without an ADR); G3 automated = gitleaks + OWASP Dependency-Check/`pnpm audit` + Trivy + isolation tests; G6 attaches automatically to PRs adding endpoints/consumers/jobs ([./ObservabilityRequirements.md](./ObservabilityRequirements.md)). The `codeowners-coverage` check runs on every PR.

CI check names are stable identifiers — branch protection references them literally, so renaming one is a CC-1-adjacent CI change owned by R-DOA:

| Check name | Gate | Content (CI stages per [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §16) |
|---|---|---|
| `build-static` | G1 | Stages 1+3: compile, Error Prone/NullAway, Spotless, ESLint/`tsc`, Modulith verification, module-docs diff |
| `openapi-diff` | G1 | Stage 5: OpenAPI generation + diff vs `main`; generated TS client staleness |
| `tests-unit` | G2 | Stage 2: all-module unit tests, JaCoCo/Vitest coverage ratchet |
| `tests-integration` | G2 | Stage 4: Testcontainers suites (Postgres 16 + pgvector, Kafka KRaft, Redis 7, MinIO), RLS isolation tests |
| `security-scan` | G3 | Stage 6: gitleaks, OWASP Dependency-Check, `pnpm audit`, license check; Trivy where an image is built |
| `docs-lint` | G7 | Link/ID/count validation over `/docs`, `/engineering-operating-system`, `/work`, MODULE.md files |
| `codeowners-coverage` | G1 | Every changed path matches a CODEOWNERS rule ([./ModuleOwnership.md](./ModuleOwnership.md) §6) |
| `repo-hygiene` | G1 | File-size limits, binary allow-list, prohibited-content patterns ([./RepositoryRules.md](./RepositoryRules.md) §6) |

## 8. Branch lifecycle against task states

The branch is the physical trace of the task lifecycle ([./DevelopmentLifecycle.md](./DevelopmentLifecycle.md)):

1. **CLAIMED → IN_PROGRESS:** agent cuts `feature/TASK-NNNN-slug` from fresh `main` and pushes within the first session.
2. **IN_PROGRESS:** commits early and often on the branch (intermediate messages are free-form; the squash message is what must conform). Rebase daily (§3.3).
3. **IN_PROGRESS → IN_REVIEW:** open the PR with the canonical description fields ([./RepositoryRules.md](./RepositoryRules.md) §1.7); CI runs the §7 check set; R-CR reviews with fresh context (task spec + diff + cited docs only).
4. **IN_REVIEW → MERGED:** all required checks green + required approvals → squash merge; branch auto-deleted; task file updated to MERGED.
5. **MERGED → VERIFIED:** post-merge CI on `main` (build, docs-lint, smoke) green. A red `main` is the merging author's top priority.
6. **VERIFIED → DONE:** DoD checklist complete ([./DefinitionOfDone.md](./DefinitionOfDone.md)); task file closed.

## 9. Example flow

```mermaid
gitGraph
  commit id: "v0.1.0 tagged earlier"
  branch feature/TASK-0142-metric-registry
  commit id: "wip: registry skeleton"
  commit id: "wip: golden dataset test"
  checkout main
  merge feature/TASK-0142-metric-registry id: "feat(eip-analytics): metric definitions registry [TASK-0142]"
  branch release/v0.3
  commit id: "tag v0.3.0" tag: "v0.3.0"
  checkout main
  branch fix/TASK-0155-dora-window-off-by-one
  commit id: "test: failing regression"
  commit id: "fix: window boundary"
  checkout main
  merge fix/TASK-0155-dora-window-off-by-one id: "fix(eip-analytics): DORA window boundary [TASK-0155]"
  checkout release/v0.3
  cherry-pick id: "fix(eip-analytics): DORA window boundary [TASK-0155]"
  commit id: "tag v0.3.1" tag: "v0.3.1"
```

## Related documents

- [./RepositoryRules.md](./RepositoryRules.md) — protected main, required checks, write-set discipline
- [./ReleaseManagement.md](./ReleaseManagement.md) — release trains, RG1–RG4, rollback decisions (R-RM)
- [./VersioningStrategy.md](./VersioningStrategy.md) — platform/SPI/API/event/DB version rules
- [./QualityGatePolicy.md](./QualityGatePolicy.md) — full gate definitions behind §7
- [./CodeReviewChecklist.md](./CodeReviewChecklist.md) — G8 verdict scale (BLOCKER/MAJOR/MINOR/NIT)
- [./DevelopmentLifecycle.md](./DevelopmentLifecycle.md) — where branching sits in the task lifecycle
- [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §16 — CI stage detail behind G1–G3
