# Quality Gate Policy

This document is the single authority on quality gates for the EIP engineering organization: what every gate G0–G8 and release gate RG1–RG4 checks, who owns it, what passes it, who may waive it, and where the evidence lives. It is read by every engineering role before its first task, by R-CR on every review, and by R-RM before every release. Gate IDs, change classes (CC-1..7), and role IDs (R-XX) are canonical and MUST be used exactly as defined here and in [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md). Note: gates govern the *engineering agents and humans building EIP* — they are unrelated to the product's 18 runtime agents (FR-082, [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md)).

## 1. Gate model

- Merge gates **G0–G8** apply per task/PR. **G0** is a task-lifecycle gate (INTAKE → READY). **G1–G8** are PR gates; a PR MUST NOT merge to `main` until every gate required for its declared change class(es) is green or validly waived (§4).
- Release gates **RG1–RG4** apply per phase release (v0.1 … v1.0) and are owned by R-RM (§6).
- Gates map onto CI stages defined in [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §14. One CI stage may feed several gates; the gate — not the stage — is the unit of pass/fail accounting.
- CI is the only accepted truth for automated results. A claim of "tests pass" without a CI run link is unverifiable and MUST be treated as a G8 BLOCKER.

## 2. Merge gate specifications (G0–G8)

### G0 — Ready
| Field | Specification |
|---|---|
| Trigger | Before a task moves INTAKE → READY (and therefore before any CLAIMED) |
| Checks | Full DoR checklist in [DefinitionOfReady.md](./DefinitionOfReady.md), including special-type pre-consults (CC-1 → R-CA, CC-4 → R-DBA) |
| Pass criteria | Every DoR item checked by R-TPM; `DoR check` field in `/work/tasks/TASK-NNNN.md` completed |
| Owner | R-TPM |
| Waiver | None. A task failing G0 is not waived; it stays INTAKE until fixed |
| Evidence | `DoR check` field in the task file |

### G1 — Build & Static
| Field | Specification |
|---|---|
| Trigger | Every PR |
| Checks | Compile (Gradle backend, `tsc` frontend); Checkstyle/ErrorProne; ESLint; license allow-list check; Spring Modulith `ApplicationModules.verify()` + committed Documenter output diff; ArchUnit rules; OpenAPI diff of committed `openapi.json` under `/docs/api/` (TestingStrategy §4, §5.2, §14 "Static" and "Architecture & contract" stages) |
| Pass criteria | All checks green. An OpenAPI breaking diff (removed path, narrowed type, removed enum value) passes only with the `api-breaking-change` label plus ADR reference (TestingStrategy §5.2) — which also makes the PR CC-1 and triggers G4 |
| Owner | R-DOA |
| Waiver | **NEVER waivable** |
| Evidence | CI run link in the PR description `test evidence` field |

### G2 — Tests
| Field | Specification |
|---|---|
| Trigger | Every PR |
| Checks | Unit (JUnit 5 + AssertJ / Vitest); integration (Testcontainers: Postgres+pgvector, Kafka, Redis, MinIO, mock OIDC — TestingStrategy §3); contract suites (event JSON Schemas §5.1, RFC 7807 / pagination / idempotency response contracts §5.2, `ConnectorContractTestKit` K1–K7 §5.3); golden dataset cases for metric changes (§7); AI eval fake-LLM subset for CC-5 (§8, §14); E2E smoke E1, E3, E7 (§10) |
| Pass criteria | All suites green; coverage ratchet met — ≥85% line / ≥75% branch on `eip-core` and `eip-analytics`, ≥75% line elsewhere (JaCoCo / Vitest coverage, TestingStrategy §1); no quarantined test introduced by the PR; the CI retry never turns a red PR green (§15) |
| Owner | R-QAA |
| Waiver | **NEVER waivable.** Coverage thresholds are ratchets: raised freely, lowered only via ADR (TestingStrategy §1) — an ADR changes the ratchet, it never waives G2 for one PR |
| Evidence | CI run link + coverage report link in the PR description |

### G3 — Security
| Field | Specification |
|---|---|
| Trigger | Every PR (automated portion); CC-2 additionally requires the manual portion |
| Checks | Automated: gitleaks over source, WireMock recordings, and simulation packs; OWASP Dependency-Check + `pnpm audit`; tenant-isolation suites (RLS probes §3, authz-matrix fast subset §12); Trivy image scan at nightly/release cadence. Manual (CC-2 only): full [SecurityChecklist.md](./SecurityChecklist.md) review and R-SA sign-off |
| Pass criteria | Zero gitleaks findings; zero critical dependency findings; isolation suites green; for CC-2, R-SA sign-off recorded on the PR |
| Owner | R-SA |
| Waiver | Automated portion **NEVER waivable**. The R-SA manual verdict is an A4 block; it can be overridden only by the human repository owner, with the override recorded in the task file |
| Evidence | CI run link; R-SA sign-off comment on the PR; scan reports retained by CI (§19) |

### G4 — Architecture
| Field | Specification |
|---|---|
| Trigger | CC-1 and CC-4 changes |
| Checks | Anchor-diff review: the change diffed against the contract anchors it touches — [../docs/product/PRD.md](../docs/product/PRD.md), [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md), [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md), [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md), [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md) (SPI sections), [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md). CC-4: R-DBA reviews Flyway migrations for expand–contract compliance, forward-only discipline, and post-migration RLS integrity (TestingStrategy §3.1) |
| Pass criteria | Owning architect approval; R-CA approval additionally for CC-1; ADR filed in `/docs/adr/` when the change is decision-level ([ADRProcess.md](./ADRProcess.md)) |
| Owner | R-CA (CC-1); R-DBA executes for CC-4 under R-CA's gate ownership |
| Waiver | Waivable **only by R-CA, and only with an ADR** recording the decision and consequences |
| Evidence | PR approval records; ADR file reference in the PR description `contract impact` field |

### G5 — Performance
| Field | Specification |
|---|---|
| Trigger | CC-3 changes (ingestion pipeline, metric engine, dashboard queries, RAG retrieval) |
| Checks | Budget check against the NFRs the change touches — NFR-010 (dashboard p50 < 500 ms / p95 < 2 s), NFR-011 (API p95 < 300 ms), NFR-003 (100k events/h + 3× burst), NFR-012 (webhook freshness 60 s p95) per [../docs/product/PRD.md](../docs/product/PRD.md) §6; `EXPLAIN (ANALYZE)` evidence for every new or changed query; Gatling load smoke for hot-path changes (TestingStrategy §11); >10% regression vs stored baseline fails |
| Pass criteria | Budgets held or improved; EXPLAIN output attached; R-PE verdict green |
| Owner | R-PE (A4 verdict on this gate) |
| Waiver | Waivable by **R-PE only**, and only with a DEBT entry in `/work/debt-register.md` naming the deferred evidence and its target phase |
| Evidence | EXPLAIN output and Gatling/baseline links in the PR description; DEBT-NNN reference when waived |

### G6 — Observability
| Field | Specification |
|---|---|
| Trigger | Changes adding endpoints, Kafka consumers, or scheduled/async jobs |
| Checks | Metrics, traces, and structured JSON logs present per [ObservabilityRequirements.md](./ObservabilityRequirements.md) and [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md) (`eip_*` metric naming); `traceparent` propagation through event envelopes (FR-127); dashboards/alerts updated for new failure modes; SLO impact statement in the PR; no secrets or raw prompts in logs (AC-093) |
| Pass criteria | Observability delta in the PR description matches the shipped code; R-OE verdict green |
| Owner | R-OE (A4 verdict on this gate) |
| Waiver | Waivable by **R-OE only**, and only with a DEBT entry in `/work/debt-register.md` |
| Evidence | `observability delta` field in the PR description; dashboard/alert diff links |

### G7 — Documentation
| Field | Specification |
|---|---|
| Trigger | Every PR |
| Checks | Docs impact declared in the PR description and applied in the same PR when behavior changes (docs-as-code, TestingStrategy §16; PRD §7.6); docs-lint (links, IDs, counts) green; metric changes carry complete definitions — purpose, formula, inputs, grain, caveats/limitations, gaming risks (FR-056) |
| Pass criteria | docs-lint green; declared docs impact fully applied or explicitly "none" |
| Owner | R-DE (A4 verdict on this gate) |
| Waiver | **NEVER waivable** |
| Evidence | `docs updated (paths)` field in the PR description; docs-lint CI link |

### G8 — Review & Done
| Field | Specification |
|---|---|
| Trigger | Every PR |
| Checks | Independent R-CR review per [CodeReviewChecklist.md](./CodeReviewChecklist.md) using the verdict scale (BLOCKER/MAJOR/MINOR/NIT); change-class declaration verified (misclassification = BLOCKER); DoD checklist ([DefinitionOfDone.md](./DefinitionOfDone.md)) satisfied at merge |
| Pass criteria | R-CR approval with zero open BLOCKERs and zero unwaived MAJORs; CC-1 requires two approvals (R-CR + owning architect, plus R-CA per G4) |
| Owner | R-CR (A4) |
| Waiver | **NEVER waivable.** The author MUST NOT merge their own PR without R-CR approval |
| Evidence | PR approval record; DoD evidence in the task file |

## 3. Change-class × gate matrix

**R** = required · **C** = conditional (required when its trigger condition holds) · **—** = skip. A PR declaring multiple classes takes the union of requirements (most demanding cell wins). G0 applies to every task before work begins.

| Class | G0 | G1 | G2 | G3 | G4 | G5 | G6 | G7 | G8 |
|---|---|---|---|---|---|---|---|---|---|
| CC-1 contract-anchor | R | R | R | R (auto) | **R** (owning architect + R-CA; ADR if decision-level) | C¹ | C² | R | **R** (two approvals) |
| CC-2 security-relevant | R | R | R | **R** (full checklist + R-SA sign-off) | C³ | C¹ | C² | R | R |
| CC-3 hot-path/perf | R | R | R | R (auto) | C³ | **R** | C² | R | R |
| CC-4 schema/migration | R | R | R | R (auto) | **R** (R-DBA; expand–contract) | C¹ | C² | R | R |
| CC-5 AI-behavior | R | R | R (+ AI eval subset) | R (auto) | C³ | C¹ | C² | R | R (+ R-AIA sign-off) |
| CC-6 docs-only | R | R (docs/static only) | R (vacuous) | R (auto) | — | — | — | **R** | R (light) |
| CC-7 standard | R | R | R | R (auto) | — | C¹ | C² | R | R |

¹ C on G5: required when the change also touches a CC-3 hot path — then declare CC-3 too.
² C on G6: required when the change adds an endpoint, consumer, or job (G6 trigger is content-based, not class-based).
³ C on G4: required when the change also touches a contract anchor or migration — then declare CC-1/CC-4 too.

## 4. Waiver rules (summary)

| Gate | Waivable? | Who | Required record |
|---|---|---|---|
| G0 | No (task stays INTAKE) | — | — |
| G1, G2, G3-automated, G7 | **Never** | — | — |
| G3-manual (CC-2) | Override only | Human repository owner | Override recorded in task file |
| G4 | Yes | R-CA only | ADR in `/docs/adr/` |
| G5 | Yes | R-PE only | DEBT entry in `/work/debt-register.md` |
| G6 | Yes | R-OE only | DEBT entry in `/work/debt-register.md` |
| G8 | **Never** | — | — |

A waiver MUST be granted before merge, MUST be referenced in the PR description, and MUST name what evidence is deferred and until when. Waivers of R-SA or R-PE A4 verdicts beyond the rows above do not exist; escalation follows the chain in [AgentResponsibilities.md](./AgentResponsibilities.md).

## 5. Merge-blocking rule set for `main`

All of the following MUST hold before any merge to `main`:

- [ ] No direct pushes — PRs only; branch named `feature/`, `fix/`, `refactor/`, `docs/`, `adr/`, or `release/` per [BranchingStrategy.md](./BranchingStrategy.md).
- [ ] Required status checks green on the head commit: G1, G2, G3-automated, G7-automated.
- [ ] G8: R-CR approval; CC-1 additionally has owning-architect and R-CA approval.
- [ ] Change class(es) declared in the PR description; R-CR has verified the declaration.
- [ ] All conditional gates for the declared class(es) green or validly waived per §4.
- [ ] OpenAPI breaking diff carries the `api-breaking-change` label + ADR reference (TestingStrategy §5.2).
- [ ] Bug-fix PRs contain the failing regression test in a commit **before** the fix commit (red→green evidence in CI or PR description).
- [ ] No new quarantined tests; if the owning module's quarantine count exceeds 10, only fix PRs may merge for that module (TestingStrategy §15).
- [ ] PR ≤ ~400 net lines excluding generated/lock files, or a declared justification.
- [ ] Test evidence is CI links, not prose claims.

## 6. Release gate protocol (RG1–RG4)

R-RM owns the go/no-go decision per release train ([ReleaseManagement.md](./ReleaseManagement.md)). Before any `vX.Y.Z` tag on a `release/v0.N` branch, R-RM MUST collect all four verdicts. A failing RG blocks the release. Individual RG checklist items MAY be PASS-WITH-WAIVER per [ReleaseManagement.md](./ReleaseManagement.md) §3 (named accepting authority + DEBT/RISK entry); an entire gate is never waived — a gate with unwaived failing items is FAIL and blocks the release. R-SA (RG2) and R-PE (RG3) verdicts are A4-binding, overridable only by the human repository owner with the override recorded.

| Gate | Verifies | Verdict | Evidence |
|---|---|---|---|
| RG1 | Phase exit criteria demonstrably met — every checklist item of the phase in [../docs/product/PRD.md](../docs/product/PRD.md) §10 | R-PO (scope acceptance) + R-TPM (status truth); R-RM records | Per-item demonstration notes linked from the release record |
| RG2 | Security certification checklist fully green (TestingStrategy §12; AC-090) **and** the NFR-071 anti-surveillance review — no individual-ranking surface exists (FR-057, AC-089); release-blocking | **R-SA (A4)** | Checklist document for the version; scan reports; anti-surveillance contract-test run |
| RG3 | Performance at phase scale targets: Gatling scenarios vs baselines (TestingStrategy §11), NFR-001–NFR-004, NFR-010–NFR-012 as applicable to the phase | **R-PE (A4)** | Gatling reports + baseline comparison from the RC nightly |
| RG4 | Operability: backup/restore or the drill relevant to the phase passes (AC-010, FR-141); runbooks and [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md)-referenced docs current | R-DOA + R-OE attest; R-DE confirms docs currency; R-RM records | Drill record; docs-lint run on the release branch |

Additionally, per TestingStrategy §16, every release candidate MUST have: the full nightly suite green on the RC, and the E2E suite (§10) passing against Docker Compose — and against the Kubernetes reference deployment from Phase 5 onward.

## 7. Evidence retention

| Evidence | Lives in |
|---|---|
| Automated gate results (G1–G3, G7) | CI run, linked from the PR description `test evidence` field; trends in the self-observability Grafana stack (TestingStrategy §19) |
| Review verdicts (G4, G8), sign-offs (R-SA, R-AIA) | PR review records |
| Waivers | ADR (`/docs/adr/ADR-NNN-*.md`) for G4; DEBT entry (`/work/debt-register.md`) for G5/G6; both referenced from the PR |
| AC demonstrations, DoD evidence, escalations/overrides | Task file `/work/tasks/TASK-NNNN.md` |
| Committed contract artifacts | `openapi.json` under `/docs/api/`; event schemas in `eip-core` resources; Modulith Documenter output |
| Release gate verdicts | Release record for the `release/v0.N` train per [ReleaseManagement.md](./ReleaseManagement.md) |

## Related documents

- [DefinitionOfReady.md](./DefinitionOfReady.md) — the G0 checklist
- [DefinitionOfDone.md](./DefinitionOfDone.md) — the G8 DoD checklist, VERIFIED vs DONE
- [CodeReviewChecklist.md](./CodeReviewChecklist.md) — R-CR's G8 procedure and verdict scale
- [SecurityChecklist.md](./SecurityChecklist.md) — G3 manual portion for CC-2
- [PerformanceChecklist.md](./PerformanceChecklist.md) / [TestingChecklist.md](./TestingChecklist.md) — G5/G2 author-side checklists
- [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md) — DEBT entries backing G5/G6 waivers
- [ADRProcess.md](./ADRProcess.md) — ADRs backing G4 decisions and waivers
- [ReleaseManagement.md](./ReleaseManagement.md) — release trains and RG mechanics
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) — tooling, CI stages, thresholds (source of record)
- [../docs/product/PRD.md](../docs/product/PRD.md) — NFR budgets and phase exit criteria (source of record)
