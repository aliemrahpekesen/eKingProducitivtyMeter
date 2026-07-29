# Definition of Done (DoD)

This document is the complete DoD checklist for EIP engineering tasks: what MUST be true before a task moves to DONE, the evidence each item requires, and how DONE differs from MERGED and VERIFIED. It is applied by the task's assigned engineering agent at close-out, checked by R-CR at G8 (merge-time items), and audited by R-TPM at sprint review. It operationalizes the product-level Definition of Done in [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md) §1 — where that list and this one overlap, both bind; neither may be weakened.

## 1. MERGED vs VERIFIED vs DONE

| State | Meaning | Who flips it | Evidence |
|---|---|---|---|
| **MERGED** | PR merged to `main` with all merge gates green per [QualityGatePolicy.md](./QualityGatePolicy.md) §5 | Merge action (after R-CR approval) | PR merge record |
| **VERIFIED** | Post-merge checks green on `main`: CI on main, docs-lint, and the E2E smoke set (E1, E3, E7 — [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §10) | Assigned engineering agent, after checking the `main` CI run | Link to the green `main` CI run in the task file |
| **DONE** | Every applicable item in §2 checked, with evidence recorded in the task file's `## DoD evidence` section | Assigned engineering agent; R-TPM audits (§5) | Task file `/work/tasks/TASK-NNNN.md` |

A task MUST NOT move to DONE directly from MERGED. If post-merge checks break on `main` because of the task, it returns to IN_PROGRESS with a fix task at top priority. VERIFIED without DONE at session end requires a handoff note.

**Close-out sequence** (the last actions of a task session, in order):

1. Confirm the `main` CI run for the merge commit is green → set VERIFIED with the link.
2. Walk §2 top to bottom; fill `## DoD evidence` per item (link, note, or "n/a — reason").
3. File DEBT/RISK entries for anything §2 accepted as a shortcut or surfaced as a risk.
4. Set the task state to DONE and update the sprint plan line for the task.
5. If any step cannot complete in this session, stop at the reached state and write a handoff note instead — never mark DONE on intent.

## 2. The DoD checklist

"Evidence" means a link or note in the task file's `## DoD evidence` section. Items marked *(if applicable)* apply when their trigger holds and MUST be marked "n/a — reason" otherwise; blank is a failure.

### Code
- [ ] Merged to `main` behind a reviewed PR; all gates for the declared change class(es) green or validly waived. *Evidence:* PR link + CI run link.
- [ ] No new compiler, Checkstyle/ErrorProne, ESLint/tsc, or deprecation warnings introduced. *Evidence:* G1 stage log link.
- [ ] Spring Modulith `verify()` and ArchUnit rules pass; committed Documenter output regenerated if module surfaces changed. *Evidence:* CI link.
- [ ] Commits follow Conventional Commits with `[TASK-NNNN]`; branch short-lived (≤ 5 working days) and deleted after merge. *Evidence:* PR commit list.

### Tests
- [ ] Task-level ACs and referenced AC-xxx are demonstrated: each AC has a passing automated test, or an explicitly test-planned manual demonstration noted per AC. *Evidence:* **AC demonstration note in the task file** — per AC: the test name/CI link, or the manual demo steps + observed result.
- [ ] New logic covered at the right pyramid layer; coverage ratchet respected (≥85%/≥75% on `eip-core`/`eip-analytics`, ≥75% elsewhere — TestingStrategy §1). *Evidence:* coverage report link.
- [ ] *(if applicable)* Persistence/Kafka/cache/auth changes carry Testcontainers integration tests; new repository tests include the TENANT_A/TENANT_B RLS probe (TestingStrategy §3). *Evidence:* test class names + CI link.
- [ ] *(if applicable)* New/changed metric has a golden dataset case with independently computed expected values (TestingStrategy §7). *Evidence:* golden case path + `goldenTest` run link.
- [ ] *(if applicable)* New/changed connector passes the full contract kit K1–K7 (TestingStrategy §5.3). *Evidence:* `connectorKit` run link.
- [ ] *(if applicable)* New/changed agent behavior (CC-5) has output schema validation and eval coverage; eval subset green (TestingStrategy §8). *Evidence:* eval harness run link + R-AIA sign-off.
- [ ] *(if applicable)* Bug fix: failing regression test committed before the fix (red→green evidence). *Evidence:* commit SHAs of red and green runs.
- [ ] No test introduced by this task is quarantined or order-dependent. *Evidence:* G2 stage link.

### Security
- [ ] Tenant isolation verified for every new table (RLS policy present), endpoint, cache key, Kafka consumer, and object-storage path introduced (FR-128/FR-129; AcceptanceCriteria §1). *Evidence:* isolation test names + CI link.
- [ ] RBAC permission(s) defined for every new endpoint/UI action; denied-path test exists (FR-122; AC-004/AC-005). *Evidence:* test names.
- [ ] No secrets, tokens, or personal data in code, fixtures, recordings, or logs; gitleaks green (TestingStrategy §12). *Evidence:* G3 stage link.
- [ ] *(if applicable)* CC-2: R-SA sign-off recorded; audit events emitted for security-relevant/config-changing actions (FR-123). *Evidence:* PR sign-off link + audit test name.
- [ ] No individual-surveillance or ranking surface introduced (FR-057/NFR-071; AC-047, AC-089). *Evidence:* anti-surveillance contract-test run link.

### Observability
- [ ] New operations emit OTel spans, Micrometer metrics (`eip_*` naming), and structured JSON logs per [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md); `traceparent` propagated through produced envelopes (FR-127). *Evidence:* metric/span names + G6 verdict or "n/a — no new operation".
- [ ] *(if applicable)* Dashboards/alerts updated for new failure modes; SLO impact statement in the PR. *Evidence:* dashboard diff link.
- [ ] No secret material or unredacted prompt content in any log path (AC-093). *Evidence:* log-assertion test name.

### Docs
- [ ] `/docs` updated in the same PR wherever behavior changed (docs-as-code; PRD §7.6); docs-lint green. *Evidence:* `docs updated (paths)` field + G7 CI link.
- [ ] *(if applicable)* OpenAPI spec regenerated and committed; metric definitions complete per FR-056; MODULE.md updated when owned tables/topics/endpoints/invariants changed. *Evidence:* file paths in the PR.
- [ ] *(if applicable)* ADR filed and Accepted for decision-level changes. *Evidence:* ADR-NNN link.

### Process
- [ ] Change class declared and confirmed by R-CR; conditional-gate waivers (if any) hold their required records (ADR / DEBT-NNN). *Evidence:* PR description + register entry links.
- [ ] Every accepted shortcut has a DEBT entry (register-or-fix rule, [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md)); every discovered risk has a RISK entry. *Evidence:* DEBT/RISK IDs.
- [ ] Task file updated: state DONE, `## DoD evidence` complete, open questions resolved or converted to TASK/RISK entries; handoff notes (if any) closed out. *Evidence:* the task file itself.
- [ ] Durable knowledge written back: anything future sessions need moved into L1–L3 (docs, MODULE.md, work registers) — not left in session context. *Evidence:* paths touched.

## 3. DoD variations by task type

| Task type | Checklist delta |
|---|---|
| Docs-only (CC-6) | Code/Tests/Security/Observability groups are "n/a — docs-only"; G7 + light G8 evidence still required; ID/link integrity via docs-lint is the test surface |
| Refactoring | Adds behavior-preservation evidence: identical test results before/after, zero contract diff (OpenAPI/event schemas unchanged), per [RefactoringPolicy.md](./RefactoringPolicy.md) |
| Bug fix | The regression-test-before-fix item is mandatory, never "n/a"; the escaped defect is recorded for sprint metrics |
| Spike/measurement | Deliverable is a written finding in the task file plus follow-up TASK/RISK/DEBT entries; code produced by a spike MUST NOT merge to `main` |

## 4. Evidence standard

- CI links are the only accepted proof for "tests pass" — prose claims are not evidence and R-CR MUST treat them as a BLOCKER at G8.
- The AC demonstration note is mandatory even when every AC is automated: it maps each AC ID to its test, so the sprint-review demo and the DoD audit need no re-derivation.
- Evidence links MUST resolve for a reader with repository access only (no links to an agent's private session state).

## 5. Sprint DoD audit (the 20% hook)

At each sprint review, R-TPM samples a random 20% of the tasks marked DONE that sprint and re-verifies this checklist item by item from the recorded evidence ([SprintReviewGuide.md](./SprintReviewGuide.md)):

- An item whose evidence is missing or whose link does not resolve **fails the audit**; the task reverts to VERIFIED and a close-out fix is scheduled in the next sprint.
- Audit failures are counted in sprint metrics (gate pass rate, escaped defects) and produce retro outputs as TASKs or RISK/DEBT entries.
- A role with audit failures in two consecutive sprints escalates per the default chain to R-CA.

## Related documents

- [QualityGatePolicy.md](./QualityGatePolicy.md) — the gates whose green status DoD items reference
- [DefinitionOfReady.md](./DefinitionOfReady.md) — G0; the plans (test/observability/docs) DoD closes out
- [CodeReviewChecklist.md](./CodeReviewChecklist.md) — R-CR's merge-time verification of DoD items
- [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md) — DEBT entries for accepted shortcuts
- [SprintReviewGuide.md](./SprintReviewGuide.md) — the DoD audit in the sprint cadence
- [ObservabilityRequirements.md](./ObservabilityRequirements.md) — the observability bar DoD checks
- [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md) §1 — product-level DoD baseline (source of record)
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) — suites, thresholds, and commands cited above (source of record)
