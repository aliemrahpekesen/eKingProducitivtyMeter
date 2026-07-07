# Testing Checklist

This is the per-PR checklist that operationalizes [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) as gate **G2** (owner: R-QAA). Implementation engineers (R-IE) and test engineers (R-TE) apply it while authoring; the Code Reviewer (R-CR) verifies it at G8. Where an item quotes a threshold, the TestingStrategy is the source of record — this checklist never redefines numbers.

## 1. Choose the right pyramid level

- [ ] The change's tests sit at the lowest level that can prove the behavior (TestingStrategy §1 targets: ~70% unit, ~20% integration, ~10% E2E of test count). Logic testable without infrastructure MUST be a unit test; a `@SpringBootTest` boot for a pure-domain assertion is a smell and is rejected in review (TestingStrategy §2).
- [ ] New behavior lands with tests in the same PR — tests are part of the write-set declared in the task spec, never a follow-up TASK.

## 2. Unit conventions

- [ ] JUnit 5 + AssertJ (backend), Vitest + Testing Library (frontend); no Hamcrest, no bare `assertEquals` (TestingStrategy §2).
- [ ] Naming `methodUnderTest_condition_expectedOutcome` or BDD `@DisplayName`; one logical assertion cluster per test.
- [ ] No mocking of owned types where avoidable: real objects or hand-rolled fakes in `testFixtures` (e.g., `InMemoryCheckpointStore`, `FakeLlmProvider`); Mockito only for third-party seams (TestingStrategy §2 table).
- [ ] Time and randomness injected: `Clock.fixed(...)`, seeded `RandomGenerator`, `EventIdGenerator` for UUIDv7.
- [ ] Tenancy visible at unit level: use the `TENANT_A`/`TENANT_B` constants from `eip-core` test fixtures.

## 3. Integration tests (Testcontainers — real infrastructure)

- [ ] Integration suites run against real dependencies via Testcontainers exactly as TestingStrategy §3 mandates: PostgreSQL 16 + pgvector (`pgvector/pgvector:pg16`, Flyway + RLS applied), Kafka KRaft, Redis 7, MinIO, mock OIDC. In-memory substitutes (H2, embedded brokers) are BLOCKERs.
- [ ] Tests tagged `@Tag("integration")`, in `<module>/src/integrationTest/java`; `testcontainers.reuse.enable=true` never in CI (TestingStrategy §3.2).
- [ ] Entities are created only through the fluent dataset builders in `testFixtures` — direct inserts bypass RLS/audit and are rejected (TestingStrategy §13).
- [ ] Every repository integration test class includes the RLS verification pattern: write as `TENANT_A`, assert invisibility as `TENANT_B` (TestingStrategy §3).
- [ ] Kafka tests assert the full canonical envelope and idempotent consumption (same `eventId` twice → exactly one state change); DLQ tests prove park + replay (TestingStrategy §3).
- [ ] Migration changes run the §3.1 suite: clean migrate, baseline upgrade, RLS-after-migration probes, forward-only (no `U` migrations).

## 4. Contract tests for anchor changes (CC-1 / CC-4)

- [ ] `/api/v1` surface changes: committed `openapi.json` diff is clean, or the PR carries the `api-breaking-change` label plus an ADR reference (TestingStrategy §5.2) — and is declared CC-1 (G4).
- [ ] Event changes: versioned JSON Schema added/updated under `eip-core` schemas; backward-compatibility checks pass; a field removal/retype without a `schemaVersion` bump fails the build (TestingStrategy §5.1; [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §5).
- [ ] Connector changes: full `ConnectorContractTestKit` K1–K7 green (TestingStrategy §5.3); new connectors subclass the kit (ArchUnit enforces it).
- [ ] Modulith/ArchUnit boundary checks green; regenerated Documenter output committed (TestingStrategy §4).

## 5. Golden datasets (any metric-engine or normalizer change)

- [ ] New or changed metrics have a golden case under `/simulation/golden/<pack>/` replayed through the real pipeline (TestingStrategy §7), plus a complete metric definition (purpose, formula, inputs, grain, caveats, gaming risks — FR-056).
- [ ] Changed expected values are **versioned with rationale**: expectations recomputed independently (spreadsheet/script committed beside the data), reviewed by the analytics stream owner, and recorded in the metric's changelog (TestingStrategy §7 rules). Silently altering a metric is impossible by design — a golden diff with no rationale is a BLOCKER.
- [ ] Golden datasets pin exact simulation pack versions (TestingStrategy §13).

## 6. Coverage ratchet

- [ ] Coverage gates hold at the TestingStrategy §1 thresholds: **≥ 85% line and ≥ 75% branch on `eip-core` and `eip-analytics`; ≥ 75% line elsewhere** (JaCoCo backend, Vitest frontend).
- [ ] Ratchets are one-way: "they may be raised, never lowered, without an ADR" (TestingStrategy §1). A PR that lowers a threshold without an ADR fails G2.
- [ ] PRs touching `eip-analytics` formula packages or `eip-core` invariants report the PIT mutation score (target ≥ 70%, gating nightly — TestingStrategy §18); a surviving mutant in a metric formula means a missing golden case.

## 7. Bug fixes: regression test first

- [ ] Every bug-fix PR contains the failing regression test in a commit **before** the fix commit, with red→green evidence in CI or the PR description ([DevelopmentLifecycle.md](./DevelopmentLifecycle.md) §7). No regression test, no merge.
- [ ] The regression test lives at the lowest pyramid level that reproduces the bug.

## 8. Tenancy-touching changes: isolation tests

- [ ] Any change touching tenancy, RLS, retrieval filters, storage prefixes, or consumer tenant handling extends the automated isolation suite (NFR-041): repository RLS probes, API cross-tenant probes, RAG isolation, MinIO prefix isolation, Kafka envelope `tenantId` handling (TestingStrategy §12; see [./SecurityChecklist.md](./SecurityChecklist.md) §2 A4).

## 9. AI-behavior changes (CC-5): eval suite

- [ ] All AI tests run without a real LLM: `FakeLlmProvider` is the default `test`-profile provider (TestingStrategy §8).
- [ ] The eval harness passes at the TestingStrategy §8 bars, quoted verbatim: **Structure validity 100% (hard) · Citation presence 100% of claims (hard) · Numeric fidelity 100% (hard) · Grounding 100% (hard) · Narrative quality trend-tracked, non-blocking.**
- [ ] The Validation Agent regression suite is green — it must flag every corrupted sample and pass every good one; any regression blocks merge (TestingStrategy §8).
- [ ] New/changed agents declare an output JSON Schema with malformed-output repair/reject tests; prompt/routing changes carry eval coverage and R-AIA sign-off (CC-5 per [QualityGatePolicy.md](./QualityGatePolicy.md) §3).

## 10. E2E smoke on PR

- [ ] Cross-cutting changes run the PR smoke subset **E1 (fresh install boot), E3 (sync), E7 (tenant isolation)** against the Compose demo stack (TestingStrategy §10, §14); the full E1–E10 set is nightly.
- [ ] PR CI wall-clock stays within the ≤ 20 minute budget — heavier suites move to nightly with a smoke representative (TestingStrategy §14).

## 11. Flaky tests

- [ ] No new quarantined test enters via this PR (TestingStrategy §16).
- [ ] A test quarantined under `@Tag("quarantine")` has an expiry of at most **5 working days** (TestingStrategy §15) and a corresponding `DEBT-NNN` entry in `/work/debt-register.md` naming the owning module and the fix-or-reimplement deadline (entry template per [AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §3.8). Quarantine without a DEBT entry violates the register-or-fix rule.
- [ ] No blanket retries: CI retries at most once for evidence; a retry never turns a red PR green (TestingStrategy §15).

## 12. Test data rules

- [ ] **No real tenant/customer data** in the repo, CI, or developer machines — ever (TestingStrategy §13). Synthetic data is always preferred over anonymized data.
- [ ] Simulated inputs come from the versioned packs in `/simulation/packs/{demo-small, demo-midsize, demo-troubled, enterprise-large}`; `demo-small` for CI smoke, `enterprise-large` for nightly load (TestingStrategy §13).
- [ ] Fixtures derived from real systems (WireMock recordings) pass `scripts/scrub-recordings` and the reviewer checklist (no names, emails, hostnames, ticket text, tokens); provenance recorded in the fixture README.

## 12.1 Local verification before pushing

The local suites mirror CI exactly (TestingStrategy §17) — "green locally, red in CI" is treated as a harness bug, not a shrug. Minimum pre-push runs:

| Change touches | Run before push (TestingStrategy §17) |
|---|---|
| Any backend code | `./gradlew check` (units + Modulith/ArchUnit + schema/OpenAPI contracts) |
| Persistence, Kafka, cache, storage, auth | `./gradlew integrationTest` for the changed modules |
| A connector | `./gradlew connectorKit --tests '*<Connector>*'` |
| Metric engine or normalizer | `./gradlew goldenTest` |
| Agent, prompt, or RAG code | `scripts/run-eval-harness --fake` |
| Frontend | `pnpm test`; cross-cutting UI flows: `pnpm e2e:smoke` |

## 13. Per-PR summary by change class

| Change class | Minimum test obligations (sections above) |
|---|---|
| CC-1 (contract anchor) | §4 contract tests + §1–§3 as applicable |
| CC-2 (security) | §8 isolation + [./SecurityChecklist.md](./SecurityChecklist.md) automated suite |
| CC-3 (perf) | §1–§3 + load evidence per [./PerformanceChecklist.md](./PerformanceChecklist.md) §8 |
| CC-4 (schema/migration) | §3 migration suite + §4 schema compatibility |
| CC-5 (AI behavior) | §9 evals + §2 unit conventions |
| CC-6 (docs-only) | none (G7 applies instead) |
| CC-7 (standard) | §1, §2, §3 as applicable; §7 if a bug fix |

## Related documents

- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) — source of record for all thresholds, suites, and tools
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §15 — event testing detail · [../docs/product/PRD.md](../docs/product/PRD.md) — FR/NFR ids cited above
- [./QualityGatePolicy.md](./QualityGatePolicy.md) · [./DefinitionOfDone.md](./DefinitionOfDone.md) · [./CodeReviewChecklist.md](./CodeReviewChecklist.md) · [./SecurityChecklist.md](./SecurityChecklist.md) · [./PerformanceChecklist.md](./PerformanceChecklist.md) · [./ObservabilityRequirements.md](./ObservabilityRequirements.md)
- [./AIValidationWorkflow.md](./AIValidationWorkflow.md) · [./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md)
