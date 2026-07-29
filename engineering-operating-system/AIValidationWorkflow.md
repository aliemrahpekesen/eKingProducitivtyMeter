# AI Validation Workflow

This document defines how work produced by AI engineering agents is validated before it becomes truth — the L0–L4 validation ladder, the evidence rules that make claims checkable, and the controls that catch hallucination and regression. It is read by every implementing role before opening a PR, by R-CR before every review, and by R-QAA/R-RM when auditing validation health. **Disambiguation (per [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §0):** the validation *levels* L0–L4 below validate the work of engineering agents building EIP; they are unrelated to the product's runtime Validation Agent (FR-087, [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md)) — which appears here only once, as a test subject in §6. They are also unrelated to the context *layers* L1–L4 of [./ContextManagementStrategy.md](./ContextManagementStrategy.md).

## 1. The validation ladder (L0–L4)

Every change climbs the ladder in order; no level substitutes for another. A level's owner MAY only pass work whose lower levels already show green evidence.

| Level | Name | Actor | Applies to | Passes when |
|---|---|---|---|---|
| **L0** | Self-check | authoring agent | every change, pre-PR | local suite green + DoD pre-check done |
| **L1** | CI gates | pipeline (owners: G1 R-DOA, G2 R-QAA, G3 R-SA, G7 R-DE) | every PR | all PR-stage checks green |
| **L2** | Independent review | R-CR (A4), fresh context | every PR | G8 APPROVE under the verdict scale |
| **L3** | Architect review | owning architect (+R-CA for CC-1) | CC-1 / CC-2 / CC-4 / CC-5 | conditional gate sign-off (G4/G5 as applicable; R-SA for CC-2, R-DBA for CC-4, R-AIA for CC-5) |
| **L4** | Human checkpoint | human repository owner | phase boundaries, releases, RG2 | recorded approval |

**L0 — Self-check.** Before opening a PR the author MUST run the local equivalents of the PR pipeline per [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §17 (`./gradlew check`, plus `integrationTest`/`connectorKit`/`goldenTest`/`pnpm test`/`scripts/run-eval-harness --fake` as the write-set demands), self-audit against [DefinitionOfDone.md](./DefinitionOfDone.md), and record the exact commands + results in the PR description's test evidence field. L0 output is a claim, not proof — L1 converts it to proof.

**L1 — CI gates.** The PR pipeline stages of [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §14 implement the automated portions of G1–G3 and G7, within the §14 PR wall-clock budget (≤ 20 minutes). A red L1 stops the ladder; nobody reviews red PRs except to help un-red them.

**L2 — Independent review.** R-CR renders a G8 verdict (BLOCKER/MAJOR/MINOR/NIT, [./CodeReviewChecklist.md](./CodeReviewChecklist.md)) under the isolation rules of §3. R-CR additionally verifies: declared change class matches the diff (misclassification = BLOCKER), diff stays inside the task's write-set, and every PR-description claim carries §2-grade evidence.

**L3 — Architect review.** Conditional gates by change class per [./QualityGatePolicy.md](./QualityGatePolicy.md): CC-1 → G4 anchor-diff review by owning architect + R-CA, two approvals at G8; CC-2 → full [SecurityChecklist.md](./SecurityChecklist.md) + R-SA sign-off; CC-4 → G4 by R-DBA + expand–contract compliance; CC-5 → §6 of this document + R-AIA sign-off. CC-3 passes G5 with R-PE's A4 verdict (EXPLAIN evidence for new queries, budget check against the cited NFR IDs).

**L4 — Human checkpoint.** The human repository owner approves phase exits (RG1, [../docs/product/PRD.md](../docs/product/PRD.md) §10), release go/no-go with R-RM, and the RG2 security certification including the NFR-071 anti-surveillance review — the release-blocking guarantee that no individual-surveillance capability ships. L4 approvals are recorded (release notes or escalation record); silent approval does not exist.

### 1.1 Failure handling per level

A failed level never "downgrades" — the work returns to the author, and re-entry starts from L0 again on the new commit.

| Failed at | Immediate effect | Return path | Recorded where |
|---|---|---|---|
| L0 | PR MUST NOT be opened | author fixes; re-run local suite | task status log |
| L1 | ladder halts; no review of red PRs | fix commit → CI re-runs; 3 consecutive red pushes SHOULD trigger a handoff + R-TPM check-in | CI history |
| L2 | `REQUEST-CHANGES`; task stays IN_REVIEW | fix or one evidence-based rebuttal round; deadlock escalates per [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §7 | review thread |
| L3 | conditional gate withheld | rework per architect findings; decision-level disagreements produce an ADR or an escalation record | PR + task file |
| L4 | release/phase exit blocked | R-RM re-plans the train; gaps become TASKs, accepted residuals become RISK entries | release record |

Waiver paths are exactly those defined in [./QualityGatePolicy.md](./QualityGatePolicy.md) §4 (G1, G2, G3-automated, G7, G8 are never waivable; G4 only by R-CA with an ADR; G5/G6 only by R-PE/R-OE with a DEBT entry) and [./ReleaseManagement.md](./ReleaseManagement.md) §3 (individual RG checklist items MAY be PASS-WITH-WAIVER with a named accepting authority and a DEBT/RISK entry; an entire release gate is never waived — a gate with unwaived failing items is FAIL and blocks the release). A MAJOR review finding MAY additionally be waived with justification recorded in the PR ([./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §3.3). Beyond those paths, the only override is the human repository owner via a recorded escalation, per the R-SA override rule.

## 2. CI is truth — evidence requirements

**Rule:** the only accepted proof that "tests pass" is a green CI run on the exact commit under review. Reviewer practice: follow the CI link in the PR, or trigger a re-run; never accept prose. **Any claim that cannot be verified from CI, the diff, or a cited committed file is a BLOCKER — not a MINOR.**

| Claim in a PR | Required evidence |
|---|---|
| "tests pass" | Link to green PR-stage CI run on the head commit |
| "covered by tests" | Named test file(s) present in the diff + green run; coverage delta from the CI report |
| "no perf impact" (CC-3) | G5 artifacts: EXPLAIN output, load-smoke numbers vs the NFR budget cited by ID |
| "migration is safe" (CC-4) | Clean-migrate + baseline-upgrade suites green ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §3.1); expand–contract stage named in rollback note |
| "docs updated" | Paths in the diff; docs-lint (G7) green |
| "evals pass" (CC-5) | AI-evals CI stage green (§6), harness report attached |
| "bug fixed" | Red-before-green regression evidence (§5.1) |
| "behavior-preserving refactor" | Tests green before and after + zero contract diff ([./RefactoringPolicy.md](./RefactoringPolicy.md)) |

Local runs (L0) never substitute for L1: "green locally" is a debugging aid; per [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §17, local/CI divergence is itself a harness bug to file. CI retries follow the flaky policy (§14/§15 there): at most one retry, only for flakiness evidence — a retry MUST NOT turn a red PR green for merge on `main`.

Evidence is durable: the PR is the audit trail. After merge, the task file's status log MUST link the merged PR so that VERIFIED evidence (CI on `main`, docs-lint, smoke) remains discoverable from `/work` alone; evidence that lives only in a session transcript does not exist ([./ContextManagementStrategy.md](./ContextManagementStrategy.md) §7).

## 3. Independent-reviewer isolation

R-CR's value is fresh context. For every review, R-CR:

- **Reads only:** the diff, the task spec `/work/tasks/TASK-NNNN.md`, the docs the task cites, CI results, and the PR description.
- **MUST NOT read:** the author's session transcript, reasoning traces, chat logs, or handoff notes for the same task. The author's intent is irrelevant; only the artifact is reviewed. If the diff cannot be judged without the author's private reasoning, the PR description or task spec is inadequate — that is itself a MAJOR finding.
- **MUST NOT edit** the PR (comments only), and the author never merges without R-CR approval. CC-1 requires two approvals (R-CR + owning architect, + R-CA).
- **SHOULD run in a separate session/agent instance** from the author — never the same session that wrote the code reviewing it.

Author rebuttals cite evidence (doc §/ID or CI run) and get one round; deadlocks escalate per [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §7.

## 4. Hallucination controls

AI-specific failure modes get mechanical countermeasures. Each control names its enforcement point:

| # | Hallucination class | Control | Enforced at |
|---|---|---|---|
| H1 | Claimed tests that were never run / don't exist | CI re-runs everything on the head commit; test evidence must name files present in the diff | L1 + L2 |
| H2 | Cited files, sections, or IDs that don't exist | docs-lint: relative-link resolution + ID-reference resolution over the full ID space (104 FR, 22 NFR, 145 FEAT, 103 AC, 18 UC, 14 ADR per [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §3) applied to task specs, PR bodies, and docs | G7 + L2 |
| H3 | Invented APIs / phantom library methods | Compile + `tsc` at G1; new runtime dependencies need the [DependencyManagement.md](./DependencyManagement.md) approval trail — an unapproved dependency in the lockfile diff is a BLOCKER | L1 + L2 |
| H4 | Restated-with-drift facts (numbers, names) | Canonical-value greps in docs-lint (GUC name, DLQ pattern, NFR figures — [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §5.2); cite-by-ID rule ([./ContextManagementStrategy.md](./ContextManagementStrategy.md) §7) | G7 + L2 |
| H5 | Fabricated benchmark/eval numbers | Only CI-produced artifacts (Gatling reports, JaCoCo, harness output) count as numbers; PR-body numbers must match the linked report | L2 + G5 |
| H6 | "Fixed" bugs that aren't | Red-before-green regression proof (§5.1) | L2 |
| H7 | Silent scope creep beyond the task | Diff-vs-write-set check; out-of-scope section of the task spec | L2 |

## 5. Regression prevention

### 5.1 Red-before-green rule

Every bug-fix PR MUST contain the failing regression test in a commit **before** the fix commit, with red→green evidence visible in CI history or reproduced in the PR description (commit SHAs + runs). A bug fix without a prior-failing test is a BLOCKER at G8. R-TE owns regression-test quality per [TestingChecklist.md](./TestingChecklist.md).

### 5.2 Standing regression nets (with the numbers that gate)

All figures below are quoted from [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) — the authority; this document adds no thresholds of its own.

- **Coverage ratchets (§1, G2):** ≥ 85% line / ≥ 75% branch on `eip-core` and `eip-analytics`; ≥ 75% line elsewhere; ratchets may be raised, never lowered, without an ADR.
- **Golden datasets (§7):** every metric change replays `/simulation/golden/<pack>/` through the real pipeline; expectations are computed independently of the engine; changing expected values requires analytics-stream-owner review + a metric-changelog entry. Per §20, there is no path to silently altering a metric.
- **Contract tests (§5):** event JSON Schemas with backward-compatibility checks and consumer version matrices; OpenAPI diff (breaking change ⇒ `api-breaking-change` label + ADR reference); connector kit K1–K7 mandatory for every connector, enforced by ArchUnit (§20).
- **Mutation thresholds (§18):** PIT nightly on `eip-analytics` formula packages and `eip-core` invariants, target mutation score **≥ 70%**; informational on PRs touching those packages, gating nightly. A surviving mutant in a metric formula means a missing golden case — fix the golden set, not just a unit test.
- **Property-based suites (§18):** checkpoint-resume, idempotency, cursor pagination, percentile, and envelope round-trip properties run in the standard suites.
- **Performance baselines (§11):** > 10% degradation vs the stored baseline on any target fails the nightly and pages the owning stream.
- **E2E nets (§10, §14):** smoke scenarios E1, E3, E7 run on every PR against the Compose demo stack; the full E1–E10 suite plus all persona journeys runs nightly.
- **Flaky policy (§15):** quarantine ≤ 5 working days; > 10 quarantined tests repo-wide freezes non-fix merges for the owning module. Quarantined tests never count as passing evidence at any ladder level.

### 5.3 Post-merge verification (MERGED → VERIFIED)

Merge is not the end of validation. A task reaches VERIFIED only when, on `main` after the merge:

- [ ] the full PR-stage CI suite is green on the merge commit,
- [ ] docs-lint is green (no link/ID/canonical-value drift introduced),
- [ ] the E2E smoke subset (E1, E3, E7) passes.

A red `main` is the repository's top priority: the merging author owns the revert-or-fix decision within 1 session, R-TPM blocks further merges to the affected module until green, and every post-VERIFIED defect is counted as an escaped defect in §7.

## 6. AI-behavior changes (CC-5)

Changes to product prompts, model routing, or anything eval-affecting in `eip-ai` climb the same ladder **plus** the product AI eval suite of [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §8:

- [ ] L0: `scripts/run-eval-harness --fake` locally (mandatory for any agent, prompt, or RAG change — §17 there).
- [ ] L1: the AI-evals CI stage (schema/fake-LLM subset on PR, full harness nightly — §14) green, with the hard bars intact: structure validity 100%, citation presence 100%, numeric fidelity 100%, grounding 100%; narrative quality is trend-tracked and non-blocking.
- [ ] L1: the product **Validation Agent regression suite** green — the corrupted-output corpus (wrong numbers, fabricated citations, tenant-leaking references, individual-ranking language) fully flagged; any regression blocks merge (§8 there).
- [ ] L2: R-CR review as usual; prompt diffs are reviewed like code diffs.
- [ ] L3: R-AIA sign-off recorded on the PR.
- [ ] No real LLM anywhere in gating tests (§8 determinism rule); the optional local-model job (NFR-013 validation, §11/§14 there) is non-blocking by design and MUST NOT be cited as merge evidence.
- [ ] CC-5 changes touching guardrails, RAG isolation, or prompt-injection defenses are also CC-2 (R-SA sign-off; adversarial corpus of §12 there in scope).

## 7. Weekly validation-health metrics

R-QAA compiles these weekly (mid-sprint and at sprint end); R-TPM tables them in the sprint review ([SprintReviewGuide.md](./SprintReviewGuide.md)). Breaches create TASKs or RISK entries — metrics nobody acts on are noise.

| Metric | Source | Healthy | Action on breach |
|---|---|---|---|
| Gate pass rate (first-attempt, per gate G1–G8) | CI + PR data | ≥ 80% | Falling rate on one gate ⇒ root-cause task for the gate owner |
| Escaped defects (bugs found after VERIFIED) | issue tracker vs task records | trending ↓ | Each escape gets a red-before-green fix + ladder-gap analysis |
| Unverifiable-claim BLOCKERs (H1–H7 catches) | R-CR review data | trending ↓ | Repeat offenders (by task type) ⇒ tighten task-spec templates |
| Coverage trend | JaCoCo/Vitest reports | ratchet never breached | Breach blocks at G2 by construction; investigate ratchet pressure |
| Mutation score (`eip-analytics` formulas, `eip-core` invariants) | PIT nightly | ≥ 70% | Below ⇒ golden-set gap task (§5.2) |
| Quarantine count | flaky-test issues | ≤ 10 repo-wide | > 10 freezes non-fix merges (policy §15) |
| AI eval hard-bar pass rate | eval harness reports | 100% | Any dip blocks CC-5 merges until restored |
| docs-lint violations on main | G7 job | 0 | Nonzero ⇒ immediate CC-6 fix task |
| PR cycle time (IN_REVIEW → MERGED) | PR data | within §5 SLAs of [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) | SLA breaches escalate per protocol |
| Review-rework ratio (PRs needing ≥ 2 review rounds) | PR data | trending ↓ | Rising ⇒ prompt/DoR quality audit ([./PromptEngineeringStandards.md](./PromptEngineeringStandards.md) §5) |

## Related documents

- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — §0 disambiguation; gate law
- [QualityGatePolicy.md](./QualityGatePolicy.md) — G0–G8/RG1–RG4 mechanics per change class
- [CodeReviewChecklist.md](./CodeReviewChecklist.md), [SecurityChecklist.md](./SecurityChecklist.md), [PerformanceChecklist.md](./PerformanceChecklist.md), [TestingChecklist.md](./TestingChecklist.md)
- [DefinitionOfDone.md](./DefinitionOfDone.md), [DevelopmentLifecycle.md](./DevelopmentLifecycle.md) — DoD and VERIFIED semantics
- [AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) — verdict scale, escalation, SLAs
- [ContextManagementStrategy.md](./ContextManagementStrategy.md), [PromptEngineeringStandards.md](./PromptEngineeringStandards.md) — upstream quality that reduces validation load
- [AIEngineeringGuide.md](./AIEngineeringGuide.md), [ReleaseManagement.md](./ReleaseManagement.md), [SprintReviewGuide.md](./SprintReviewGuide.md)
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) — the authority for every threshold quoted here
- [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) — docs-lint rationale and ID inventory
