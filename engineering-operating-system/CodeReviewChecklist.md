# Code Review Checklist — Gate G8

This is R-CR's operational checklist for every PR review (gate G8). R-CR reads it before every review round and works through every group; the verdict scale in §4 is the only vocabulary for findings. R-CR holds A4 authority: an independent, blocking verdict that the author cannot merge around. This checklist reviews the *engineering* of EIP — product runtime agents (FR-082) are review *subjects* here, never reviewers.

## 1. Review inputs and independence rules

- R-CR reviews with **fresh context**: the task spec (`/work/tasks/TASK-NNNN.md`), the diff, and the documents the task spec cites — nothing else.
- R-CR MUST NOT read the author's session reasoning, chat history, or handoff drafts; independence is the point of L2 validation.
- R-CR MUST NOT edit the PR — comments and verdicts only. The author fixes; R-CR re-verifies.
- The author MUST NOT merge without R-CR approval. CC-1 requires two approvals: R-CR + owning architect (+ R-CA per G4).
- CI is the only accepted truth for "tests pass". R-CR re-runs or requires CI links; any unverifiable claim of testing/verification is a **BLOCKER**.
- **SLA: one session.** A review round completes within one session of claiming the review. If it cannot, R-CR writes a handoff note and R-TPM reassigns or reschedules; a PR MUST NOT sit unreviewed across a sprint boundary.

## 2. Review procedure

1. Read the task spec: ACs, change class(es), write-set, out-of-scope.
2. Read the cited spec sections (context pack anchors) — not the whole documents.
3. Verify CI status and open the linked runs (G1–G3, G7 evidence).
4. Walk the diff against §3A–§3J below, recording findings with §4 verdicts.
5. Verify the change-class declaration against the diff (misclassification = BLOCKER).
6. Post the verdict summary (counts per severity) and approve or request changes.
7. On re-review, verify only the fixes and any new commits — no re-opening settled NITs.

## 3. The checklist

### A. Correctness vs task spec
- [ ] Every acceptance criterion in the task spec (and each cited AC-xxx from [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md)) is implemented and evidenced — no silent scope drops, no unrequested scope adds beyond the declared write-set.
- [ ] The diff stays inside the declared write-set; out-of-write-set changes are either boy-scout-scale within the task's modules or a **BLOCKER** (single-writer rule).
- [ ] Edge cases named in the task spec (empty inputs, tenant boundaries, retry paths) are handled, not TODO'd.

### B. Change class and contract-anchor conformance
- [ ] Declared change class(es) match the diff. Misclassification (e.g., an RLS policy edit without CC-1, a Flyway file without CC-4) is a **BLOCKER**.
- [ ] Any touched contract surface is diffed against its anchor: `/api/v1` shape vs [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md); envelope/topics/schemaVersion vs [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md); entity semantics vs [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md); SPI shape vs [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md). Undeclared anchor deviation is a **BLOCKER**; the docs-first rule means the doc/ADR change lands first or in the same PR.
- [ ] Event changes bump `schemaVersion` per EventModel rules; OpenAPI breaking diffs carry the `api-breaking-change` label + ADR.

### C. Tenancy and RLS
- [ ] Every new table carries `tenant_id` with an RLS policy (FR-128); no query path bypasses RLS for tenant-owned data.
- [ ] New repository code has the TENANT_A/TENANT_B invisibility test (TestingStrategy §3); new cache keys, MinIO prefixes, and Kafka processing keys are tenant-scoped (FR-129).
- [ ] Cross-tenant lookups return 404, not 403 (AC-001 — no existence disclosure).

### D. Error handling
- [ ] API error paths produce RFC 7807 problem+json with stable `type` and a correlation ID (FR-125, AC-006); no ad-hoc error bodies.
- [ ] Errors flow through the module's sealed error taxonomy — enumerated error types, not raw exception strings; no stack-trace-as-message (kit K3 standard); no swallow-and-log-and-continue on data-integrity paths.
- [ ] Failure paths are typed and actionable: retryable vs terminal distinguished; poison paths route to `<group>.dlq`, never infinite retry.

### E. Test adequacy
- [ ] Bug fixes show red→green: the failing regression test is in a commit **before** the fix commit, with CI evidence — missing is a **BLOCKER**.
- [ ] New logic has tests at the right layer; coverage ratchet respected — a ratchet decrease without an ADR is a **BLOCKER** (TestingStrategy §1).
- [ ] Conventions hold: no `@SpringBootTest` for domain units, no mocking of owned types, AssertJ not bare asserts (§2); entities created via dataset builders, not raw SQL (§13); pinned `Clock`, seeded randomness.
- [ ] Metric changes update a golden case (§7); connector changes run kit K1–K7 (§5.3); CC-5 changes show the eval subset green (§8). No new quarantined tests.

### F. Observability
- [ ] New endpoints/consumers/jobs emit metrics (`eip_*`), spans, and structured JSON logs per [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md); `traceparent` propagates through produced envelopes (FR-127).
- [ ] The PR's `observability delta` field matches the code; new failure modes have dashboard/alert updates or a G6 waiver reference.

### G. Docs
- [ ] Behavior changes update `/docs` in the same PR (docs-as-code); `docs updated (paths)` field is accurate; docs-lint green.
- [ ] Metric changes ship the full FR-056 definition; module-surface changes update MODULE.md.

### H. Security red flags
- [ ] No hardcoded secrets, tokens, or credentials anywhere in the diff, including test fixtures and WireMock recordings (scrub policy, TestingStrategy §13).
- [ ] No PII, secret material, or unredacted prompt content in log statements or error messages (AC-093); credentials never returned by any API (FR-009, AC-017).
- [ ] Every new endpoint/mutation has a server-side permission check (FR-122) and a denied-path test; audit events emitted for security-relevant actions (FR-123).
- [ ] Nothing introduces an individual-ranking or per-person activity surface (FR-057/NFR-071) — this is a **BLOCKER** with no waiver path anywhere in the system.

### I. Performance smells
- [ ] No N+1 query patterns; new queries on hot paths carry `EXPLAIN` evidence (G5 for CC-3).
- [ ] List endpoints use cursor pagination (FR-125) — no unbounded `findAll`, no offset pagination, no unbounded in-memory collections over NFR-002-scale data.
- [ ] New query patterns have supporting indexes or a recorded R-DBA opinion; no synchronous heavy work inside Kafka consumers or request threads that belongs on `eip-workers`.

### J. Style and standards
- [ ] Conforms to [CodingStandards.md](./CodingStandards.md); Conventional Commits with `[TASK-NNNN]`; PR description fields complete (task ref, class, what/why ≤ 10 lines, contract impact, test evidence, docs, observability delta, rollback note).
- [ ] PR size ≤ ~400 net lines (excluding generated/lock files) or justification declared.

## 4. Verdict scale usage

| Verdict | Meaning | Merge effect |
|---|---|---|
| **BLOCKER** | Violates a never-waivable gate, a contract anchor, a tenancy/security invariant, the regression-test rule, the change-class declaration, or makes an unverifiable claim | Gate fails; MUST be fixed |
| **MAJOR** | Real defect or standards violation that has a legitimate waiver path | Fix, or explicit waiver with justification recorded on the PR |
| **MINOR** | Should fix; does not threaten correctness/contracts | Fix now or file a DEBT entry |
| **NIT** | Style/preference within standards | Author's discretion; never blocks |

**BLOCKER vs MAJOR rule:** ask "does a waiver path exist in [QualityGatePolicy.md](./QualityGatePolicy.md) §4, and is the risk contained to this PR's scope?" If no waiver path exists (G1/G2/G3-auto/G7/G8 substance, anchors, tenancy, FR-057) → BLOCKER. If R-CA/R-PE/R-OE could legitimately waive it with an ADR/DEBT record (missing dashboard update, deferred load evidence on a warm path) → MAJOR. Never downgrade to MINOR to avoid a discussion; never inflate a NIT to force a preference.

**Approval rule:** approve only with zero open BLOCKERs and zero unwaived MAJORs. Record the verdict summary (counts per severity) in the review comment so sprint metrics (gate pass rate) are computable.

## 5. What R-CR does NOT do

- **Does not rewrite code.** Comments only; even one-character fixes go back to the author.
- **Does not re-litigate accepted ADRs or documented architecture.** Conformance is checked against the decision as written; disagreement with the decision goes to R-CA via [ADRProcess.md](./ADRProcess.md), on R-CR's own time, never as a review finding.
- **Does not re-run the design phase.** Alternative approaches are NITs unless the implemented approach violates a documented constraint.
- **Does not verify by trust.** No "author says tested locally" — CI links or re-run.
- **Does not review own-authored code**, and does not review the same PR as both R-CR and owning architect (CC-1's two approvals MUST be two distinct agents/humans).
- **Does not expand scope.** Missing adjacent improvements become TASK or DEBT suggestions, not blocking findings.

## Related documents

- [QualityGatePolicy.md](./QualityGatePolicy.md) — G8 in the gate system; waiver table behind BLOCKER/MAJOR
- [DefinitionOfDone.md](./DefinitionOfDone.md) — the DoD items R-CR verifies at merge
- [SecurityChecklist.md](./SecurityChecklist.md) — the full CC-2 review R-SA performs (R-CR checks §H red flags only)
- [PerformanceChecklist.md](./PerformanceChecklist.md) — author-side detail behind §I
- [CodingStandards.md](./CodingStandards.md) — the standard §J checks against
- [AIValidationWorkflow.md](./AIValidationWorkflow.md) — R-CR as validation level L2
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) — test conventions cited in §E (source of record)
- [../docs/product/PRD.md](../docs/product/PRD.md) — FR/NFR IDs cited throughout (source of record)
