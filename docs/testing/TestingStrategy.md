# Testing Strategy

This document defines the testing strategy for the Engineering Intelligence Platform (EIP): what we test, at which layer, with which tools, and which gates a change must pass before merge and release. It applies to the entire monorepo (`/backend`, `/frontend`, `/infra`, `/simulation`). Architecture context is in `../architecture/ArchitectureOverview.md`, the canonical entities in `../architecture/DomainModel.md`, the build sequencing in `../implementation/PhaseBasedImplementationPlan.md`, and operational verification in `../operations/OperationsGuide.md`.

Guiding principles:

1. **Determinism first.** Every layer must be runnable offline and air-gapped, exactly like the product itself. No test may call a real SaaS API or a real LLM.
2. **Simulation is a first-class test asset.** The simulation connector and `/simulation` data packs are not demo sugar; they are the substrate for analytics correctness, AI evals, and E2E tests.
3. **Contracts over trust.** Module boundaries (Spring Modulith), event payloads (JSON Schema), REST surface (OpenAPI 3), and connector behavior (contract test kit) are all verified mechanically in CI.
4. **Team-level, anti-surveillance posture is tested too.** Tests assert that individual-ranking outputs do not exist (see §8 and §12).

## 1. Test pyramid, ratios, and coverage goals

| Layer | Scope | Tooling | Target share of test count | Coverage / volume goal | Runs on |
|---|---|---|---|---|---|
| Unit | Single class/function, pure domain logic | JUnit 5, AssertJ / Vitest | ~70% | ≥85% line, ≥75% branch on `eip-core`, `eip-analytics`; ≥75% line elsewhere | Every PR |
| Integration | Module + real infrastructure (Testcontainers) | JUnit 5 + Testcontainers | ~20% | Every repository, Kafka consumer/producer, cache path, and connector covered | Every PR |
| Architecture/contract | Modulith boundaries, JSON Schemas, OpenAPI, connector kit | Spring Modulith test support, ArchUnit, custom kit | (counted with integration) | 100% of modules, events, endpoints, connectors | Every PR |
| E2E / journey | Full stack via UI and API | Playwright, Docker Compose demo stack | ~10% | All persona key journeys (§9), ~10 core scenarios (§10) | PR (smoke subset) + nightly (full) |
| Performance | Throughput, latency, concurrency | Gatling | Out of pyramid | NFR targets table (§11) | Nightly + pre-release |
| Security | AuthZ matrix, isolation, scanning, prompt injection | Custom suites + scanners | Out of pyramid | Checklist §12 fully green | PR (fast subset) + nightly |

Coverage is measured with JaCoCo (backend) and Vitest coverage (frontend) and enforced as a merge gate (§16). Coverage thresholds are ratchets: they may be raised, never lowered, without an ADR.

## 2. Unit testing conventions

Layout and ownership:

| Location | Contains | Owned by |
|---|---|---|
| `<module>/src/test/java` | Unit tests for that module | Owning stream |
| `<module>/src/testFixtures/java` | Dataset builders, fakes, shared constants (published to dependent modules) | Owning stream |
| `<module>/src/integrationTest/java` | Testcontainers suites (`@Tag("integration")`) | Owning stream |
| `/backend/eip-app/src/gatling` | Performance scenarios | DevX/infra + owning stream |
| `/frontend/src/**/*.test.tsx` | Vitest component/hook tests | Frontend |
| `/frontend/e2e` | Playwright journeys and E2E scenarios | Frontend + owning stream |
| `/simulation/golden` | Golden datasets with expected metric values | Analytics |

Conventions:

- **Framework:** JUnit 5 (`@ParameterizedTest` encouraged for formula-style logic), AssertJ for all assertions. No Hamcrest, no bare `assertEquals`.
- **No mocking of owned types where avoidable.** If a collaborator is an EIP-owned class, use the real object or a hand-rolled in-memory fake living next to the production interface (e.g., `InMemoryCheckpointStore implements CheckpointStore`). Mockito is reserved for third-party interfaces we do not own (HTTP clients, LLM provider SPI edges) and for verifying interactions that have no observable state.

  | Collaborator | Preferred double | Example |
  |---|---|---|
  | Owned domain object | The real object | `WorkItem`, `Sprint`, metric value objects |
  | Owned port/SPI | Hand-rolled fake in `testFixtures` | `InMemoryCheckpointStore`, `FakeLlmProvider`, `InMemoryVectorStore` |
  | Infrastructure at integration level | Testcontainers (real) | Postgres, Kafka, Redis, MinIO, mock OIDC |
  | Third-party HTTP API | WireMock recording/stub | Jira, GitHub, SonarQube APIs |
  | Third-party interface, unit level | Mockito mock | Low-level HTTP client, clock-adjacent library seams |
- **Naming:** `methodUnderTest_condition_expectedOutcome` or BDD-style `@DisplayName`. One logical assertion cluster per test.
- **Domain purity:** `eip-core` and metric formula classes in `eip-analytics` must be testable without Spring context. A unit test that boots `@SpringBootTest` is a smell and is rejected in review.
- **Time and randomness:** all production code takes `Clock` / seeded `RandomGenerator` via injection. Tests pin `Clock.fixed(...)`. UUIDv7 event IDs are generated through an injectable `EventIdGenerator`.
- **Tenancy in units:** domain objects carry `tenantId`; unit tests use the constants `TENANT_A`/`TENANT_B` from `eip-core` test fixtures so isolation bugs are visible even at unit level.
- **Frontend units:** Vitest + Testing Library; test component behavior via roles/labels, never implementation details or snapshot-only tests for logic.

## 3. Integration testing (Testcontainers)

Integration tests run against real dependencies via Testcontainers, one shared container set per Gradle module (singleton containers, `@ServiceConnection`):

| Dependency | Container | Notes |
|---|---|---|
| PostgreSQL 16 + pgvector | `pgvector/pgvector:pg16` | Flyway migrations applied; RLS enabled exactly as production |
| Kafka (KRaft) | `apache/kafka` KRaft mode | Topics auto-created with `eip.` prefix; per-test consumer groups |
| Redis 7 | `redis:7-alpine` | Redisson locks, rate-limit state, cache tests |
| MinIO | `minio/minio` | S3 abstraction tests, artifact storage, raw blob staging |
| Mock OIDC | `ghcr.io/navikt/mock-oauth2-server` | Issues tokens with tenant/role claims mirroring Keycloak realm mapping |

Conventions:

- **Tag** integration tests `@Tag("integration")`; Gradle task `integrationTest` is separate from `test`.
- **Dataset builders:** every module exposes fluent builders in `src/testFixtures` (Gradle test fixtures), e.g. `aTenant().withTeam(aTeam().withSprint(...))`, `aWorkItem().ofType(STORY).inState("In Progress").blockedFor(days(3))`, `aPullRequest().opened(t0).merged(t0.plusHours(30))`. Builders write through real repositories so RLS and auditing are exercised. No raw SQL fixtures except for migration tests.
- **RLS verification pattern:** every repository integration test class includes at least one test that writes as `TENANT_A` and asserts invisibility as `TENANT_B`.
- **Kafka tests** assert the full envelope (`eventId, tenantId, source, entityType, entityId, eventType, occurredAt, ingestedAt, schemaVersion, payload, traceparent`) and idempotent consumption: publishing the same `eventId` twice must produce exactly one state change.
- **DLQ tests:** poison messages must land on `<group>.dlq` with error metadata, and replay must succeed after the fix (mirrors the runbook in `../operations/OperationsGuide.md`).

### 3.1 Database migration testing

Flyway migrations are production code and get their own suite in `eip-app`:

- **Clean migrate:** empty database → full migration chain → schema snapshot compared against the committed expected snapshot (drift fails).
- **Baseline upgrade:** for every supported previous release, a committed `pg_dump` baseline is restored and migrated forward; data-preserving assertions run on seeded rows (this is what makes the rolling upgrade promise in `../operations/OperationsGuide.md` §7 testable).
- **RLS after migration:** every migration run is followed by the RLS probe suite — a migration that drops or weakens a policy fails immediately.
- **Forward-only discipline:** no `U` (undo) migrations; a test asserts none exist. Reverting means a new forward migration.
- **Partitioning:** partition-creation and pruning functions are tested across month boundaries and leap cases with a pinned clock.

### 3.2 Container hygiene

Singleton containers per Gradle module keep integration wall-clock low; `testcontainers.reuse.enable=true` is recommended locally and forbidden in CI (clean state per run). Tests must never depend on execution order or leak data across methods — each test creates its own tenant or truncates via the builder API's cleanup hooks.

## 4. Architecture verification (Spring Modulith)

A dedicated `ModularityTests` class in `eip-app` runs on every PR:

- `ApplicationModules.of(EipApplication.class).verify()` — no cyclic dependencies, no access to another module's internals; only published APIs and events cross boundaries.
- Documenter output (module canvases, C4 component diagrams) is regenerated and committed; a dirty diff fails CI, keeping `../architecture/ArchitectureOverview.md` §5 honest (docs-as-code).
- ArchUnit rules: no module other than `eip-tenancy` touches Keycloak/RBAC internals; no module other than `eip-ai` imports LangChain4j; `eip-core` depends on nothing but the JDK and shared libraries; controllers only in `eip-app`; no `java.time.LocalDateTime` in domain (must be `Instant`/zoned); no direct `KafkaTemplate` use outside designated event publishers.
- Modulith event publication tests verify transactional outbox behavior: a domain event is published if and only if the surrounding transaction commits.

## 5. Contract testing

### 5.1 Event payload contracts

- Every Kafka event type has a versioned JSON Schema in `/backend/eip-core/src/main/resources/schemas/events/<entityType>/<eventType>-v<schemaVersion>.json` (in-repo; no external registry required on-prem).
- CI validates: (a) every published event class serializes to a payload valid against its schema, using generated exemplar instances; (b) schemas are backward-compatible — a schema change that removes/retypes a field without a `schemaVersion` bump fails the build; (c) every consumer declares which schema versions it accepts, and a matrix test feeds it each version.

### 5.2 OpenAPI contract tests

- springdoc generates `openapi.json` at build time; it is committed under `/docs/api/` and diffed in CI — breaking changes (removed path, narrowed type, removed enum value) fail unless the PR carries the `api-breaking-change` label plus an ADR reference.
- Response contract tests assert RFC 7807 problem+json on all error paths, cursor pagination envelope shape, and idempotency-key semantics on mutating batch endpoints (same key + same body → same result, no duplicate side effects).

### 5.3 Connector contract test kit

Every connector — built-in or custom — must pass `ConnectorContractTestKit`, an abstract JUnit 5 test class shipped by `eip-connectors` (test fixtures). Implementors subclass it and provide their connector plus a fixture environment. The kit's mandatory cases:

| # | Case | Verifies |
|---|---|---|
| K1 | Config validation | `validate()` rejects each invalid permutation of the connector's JSON Schema config with field-level errors; accepts the reference config |
| K2 | Test-connection success | `testConnection()` returns success with capability/version metadata against a healthy fixture endpoint |
| K3 | Test-connection failure | Wrong credentials, unreachable host, and TLS failure each produce a typed, actionable failure (no stack-trace-as-message), and no secret appears in the error |
| K4 | Incremental checkpoint resume | `incrementalSync(checkpoint)` after a simulated crash mid-stream resumes from the persisted checkpoint with zero loss and zero duplicate canonical writes |
| K5 | Rate-limit honoring | Fixture returns 429/`Retry-After`; connector backs off (exponential + jitter), never exceeds the configured request budget, and records rate-limit metrics |
| K6 | Dedup on re-sync | A `fullSync()` over already-ingested data results in idempotent upserts: canonical entity count unchanged, `ExternalRef` mapping stable, no duplicate domain events |
| K7 | Simulation determinism | With simulation/mock mode enabled and a fixed seed, two runs emit byte-identical event sequences (ordering per `tenantId:entityId` key included) |

The kit also asserts `healthCheck()` state transitions and that webhook intake (where supported) and polling converge to the same canonical state.

## 6. Connector testing with recorded fixtures

- **WireMock recordings per tool API.** For Jira, Confluence, GitHub, GitLab, Bitbucket, SonarQube, Artifactory, Prometheus, and the Generic CI/CD family, we keep sanitized WireMock stub recordings under `/backend/eip-connectors/src/test/resources/wiremock/<connector>/`. Recordings are captured once against sandbox instances, then scrubbed (tokens, hostnames, personal data) by `scripts/scrub-recordings` before commit; CI runs a secret-exposure scan over the recordings directory.
- Recording refresh is a deliberate, reviewed act (a nightly "recording drift" job replays connectors against optional live sandboxes where network policy allows and reports divergence; it never blocks PRs and is skipped entirely in air-gapped CI).
- **Simulation mode as a test data source.** Every connector supports simulation/mock mode per the Connector SPI. Analytics, RAG, and report tests consume simulated event streams rather than recordings, because simulation packs offer controlled shape (team count, sprint cadence, incident rates) and deterministic seeds. Recordings answer "do we parse the real API correctly?"; simulation answers "does everything downstream compute correctly?".

## 7. Analytics correctness testing (golden datasets)

Metric engines are tested against **golden datasets**: fixed simulated event sequences with hand-computed expected metric values, stored under `/simulation/golden/<pack>/` as (events, expected-metrics) pairs. A golden test replays the events through the real ingestion → normalization → metric pipeline (Testcontainers) and asserts outputs exactly (or within a stated tolerance for rate metrics).

Example golden cases (illustrative rows; the full set covers every metric in the canonical set — flow, DORA, quality, delivery risk, ops, team health):

| Golden case | Fixed input (simulated) | Metric | Expected output |
|---|---|---|---|
| `flow/cycle-time-basic` | 5 Stories: enter "In Progress" Mon 09:00, done after 2, 3, 3, 4, 8 calendar days | Cycle time p50 / p85 | p50 = 3d, p85 = 4d (percentile method documented in metric definition) |
| `flow/cycle-time-blocked` | 1 Story in progress 6d, of which 2d in a blocked WorkflowState | Cycle time; blocked time; flow efficiency | cycle = 6d, blocked = 2d, flow efficiency = 66.7% |
| `flow/velocity-two-sprints` | Sprint 1: committed 30 pts, done 24; Sprint 2: committed 28, done 28 | Velocity; sprint predictability | velocity = [24, 28]; predictability = [80%, 100%] |
| `flow/scope-churn` | Sprint of 10 items; 2 added and 1 removed after start | Scope churn | 30% (3 changed / 10 committed) |
| `dora/deploy-frequency` | 12 successful Deployments to Environment `prod` across 30 days | Deployment frequency | 0.4/day (2.8/week) |
| `dora/lead-time-for-changes` | 4 Commits merged → deployed after 10h, 20h, 30h, 40h | Lead time for changes (median) | 25h |
| `dora/cfr` | 20 prod Deployments; 3 cause Incidents linked within the failure window | Change failure rate | 15% |
| `dora/mttr` | 3 Incidents restored after 1h, 2h, 6h | MTTR (mean) | 3h |
| `quality/bug-aging` | 4 open Bugs aged 2, 5, 10, 40 days at as-of date | Bug aging buckets | 2×"<7d", 1×"7–30d", 1×">30d" |
| `risk/release-readiness` | Release with 90% scope done, quality gate green, 1 open critical SecurityFinding | Release readiness score | Matches the published formula in the metric definition; critical finding caps score at "not ready" |
| `flow/throughput-weekly` | 14 items done across 2 weeks (9 in week 1, 5 in week 2) | Throughput | [9, 5] items/week |
| `flow/lead-time` | 3 Stories created → done after 5, 10, 15 days | Lead time (median) | 10d |
| `quality/escaped-defects` | Sprint with 8 Stories done; 2 Bugs later linked to them from production | Escaped defects | 2 (rate 25%) |
| `team/knowledge-concentration` | Repository where 85% of Commits in 90 days touch files owned by one Member | Knowledge concentration (bus factor) | Team-level flag "high concentration"; output contains no per-individual ranking |

Rules: golden expectations are computed independently of the engine (spreadsheet or script committed beside the data); a change in expected values requires review by the analytics stream owner and an entry in the metric's changelog; every metric definition's documented formula, grain, and caveats are the authority the test encodes.

## 8. AI and agent testing

All AI tests run without any real LLM:

- **Fake LLM provider.** `FakeLlmProvider` implements the LLM provider SPI with scripted, deterministic responses (matched by prompt fingerprint) and records prompts for assertion. It is the default provider in `test` profile and enforces token budgets so budget/guardrail logic is exercised.
- **Schema validation of agent outputs.** Every canonical agent (Data Ingestion, Data Quality, Engineering Metrics, Delivery Risk, Sprint Review, Release Notes, Documentation, Use Case Diagram, Architecture Diagram, Executive Summary, Incident Analysis, Code Quality, Team Health, RAG Retrieval, Report Composition, Validation, Security Review, Configuration Assistant) declares a JSON Schema for its structured output; tests validate fake-provider outputs and — critically — verify that malformed LLM output triggers the repair/reject path, not a crash or silent acceptance.
- **Eval harness.** `eip-ai` ships an offline eval harness: golden prompts and reference responses generated from simulation data packs (e.g., "Sprint Review for simulated Team Alpha, Sprint 14"). Evals run nightly with the fake provider (regression) and optionally against a local Ollama/vLLM model in a dedicated non-blocking job. Scoring dimensions:

  | Dimension | Checks | Pass bar |
  |---|---|---|
  | Structure validity | Output conforms to the agent's JSON Schema | 100% (hard) |
  | Citation presence | Every factual claim cites a source entity | 100% of claims (hard) |
  | Numeric fidelity | Numbers in narrative match metric store values exactly | 100% (hard) |
  | Grounding | No entity mentioned that is absent from the retrieval set | 100% (hard) |
  | Narrative quality | Rubric-scored coherence/usefulness (model-judged, local model) | Trend-tracked, non-blocking |
- **Validation Agent regression suite.** The Validation Agent (which checks other agents' outputs) has its own golden suite: a corpus of known-good and deliberately corrupted agent outputs (wrong numbers, fabricated citations, tenant-leaking references, individual-ranking language). The suite asserts the Validation Agent flags every corrupted sample and passes every good one; any regression blocks merge.
- **RAG tests:** permission-aware retrieval tests assert that a query by a `TEAM_LEAD` of Team A never retrieves chunks whose metadata belongs to Team B or `TENANT_B`; incremental re-index tests assert updated documents replace stale chunks.
- **MCP tests:** MCP server capability tests assert allow-listing and per-capability RBAC + audit; MCP client tests run against a local stub MCP server.

## 9. Frontend testing

- **Vitest + Testing Library** for components/hooks: TanStack Query behavior with mocked transport (MSW), i18n key presence, ECharts option-building logic tested as pure functions (chart options in, assertions on series/axes out — no canvas assertions).
- **Playwright E2E journeys** map 1:1 to persona key journeys from `../product/Personas.md`:

| Persona | Journey under test |
|---|---|
| Deniz (Platform Administrator) | Onboard a connector: configure → validate → test connection → first sync green in Connector Health |
| Mira (Engineering Manager) | Open Delivery Health dashboard → drill into an at-risk Epic → read Delivery Risk explanation with citations |
| Sam (Team Lead) | Open Sprint dashboard → generate Sprint Review via agent → find artifact in artifact library |
| Arda (Developer) | View team Kanban flow and PR/review flow (aggregate) → confirm no individual-ranking view exists |
| Leyla (Product Manager) | Roadmap/Initiative progress view → scope churn drill-down |
| Jonas (Release Manager) | Release readiness score → generate Release Notes → export |
| Priya (SRE / Ops) | Ops dashboard: incident frequency, SLO health, alert noise |
| Helena (CISO) | Audit log viewer → LLM call audit → security finding aging |
| Kenji (VP Engineering) | Executive Summary generation → org rollup dashboard |
| Rosa (Agile Coach) | Cross-team flow comparison with caveats visible |

- Accessibility smoke (axe-core) runs on every journey's landing screen.

## 10. End-to-end suite against the Docker Compose demo stack

Nightly (full) and per-PR (smoke: E1, E3, E7), Playwright drives the complete demo stack from `/infra/docker-compose` (Postgres+pgvector, Kafka, Redis, MinIO, Keycloak, backend, workers, frontend, Ollama-stub) with a seeded simulation pack:

| # | Scenario |
|---|---|
| E1 | Fresh install boot: stack up, Flyway migrations apply, health endpoints green, login via Keycloak |
| E2 | Connector onboarding: add simulation connector via UI, validate config, test connection, enable |
| E3 | Sync: full sync then incremental sync; checkpoint browser shows progress; raw → canonical counts match pack manifest |
| E4 | Dashboard render: sprint/kanban/DORA/quality dashboards render seeded metrics with non-empty charts and stated caveats |
| E5 | Report generation: Sprint Review agent job → artifact in library → downloadable export from MinIO |
| E6 | RBAC denial: `MEMBER` requests `connector:manage` and tenant admin screens → 403 problem+json in API, guarded route in UI |
| E7 | Tenant isolation: seed two tenants; all APIs, dashboards, RAG answers, and artifacts for Tenant A show zero Tenant B data |
| E8 | Failure and recovery: kill a worker mid-sync; sync resumes from checkpoint; DLQ replay clears an injected poison message |
| E9 | Webhook intake: simulated webhook updates a WorkItem; dashboard reflects it within the freshness SLO |
| E10 | Audit trail: every action from E2–E9 appears in the audit log with actor, tenant, and outcome; LLM calls appear with token/cost records |

## 11. Performance testing

Gatling scenarios (in `/backend/eip-app/src/gatling`) run nightly against a Compose profile sized like the reference single-node install, and pre-release against the Kubernetes reference deployment:

| Scenario | Load shape | Target (from NFRs) |
|---|---|---|
| Ingestion throughput | Simulation connector firehose | Sustain 100,000 events/hour with consumer lag < 60 s and zero DLQ growth |
| Ingestion burst | Simulation connector firehose at 3× the sustained rate: 300,000 events/hour for 15 minutes (NFR-003) | Zero acknowledged-event loss (backpressure via Kafka permitted); consumer lag returns to baseline (< 60 s) after the burst ends |
| Metric query latency | 50 concurrent users hitting metric APIs | p95 < 500 ms, p99 < 1.5 s |
| Concurrent dashboard load | 200 users opening dashboards (cold + warm cache) | p95 initial render API bundle < 2 s; Redis hit rate > 80% warm |
| Report generation under load | 20 concurrent agent report jobs (fake LLM) | No starvation of interactive APIs; job queue drains within SLO |
| Sync burst | Full sync of large pack during dashboard load | Interactive p95 degradation < 20% |

Regression rule: >10% degradation vs. the stored baseline on any target fails the nightly and pages the owning stream.

NFR-013 (AI/agent latency and token/cost budgets) is deliberately **not** certified by these scenarios: the report-generation load test runs against `FakeLlmProvider`, whose latency is not representative of a real model. NFR-013 is validated out-of-band by the optional, non-blocking local-model job in the AI evals stage (§8, §14) against a local Ollama/vLLM model; its results are trend-tracked and reported, never merge-gating.

## 12. Security testing checklist

- [ ] **AuthZ matrix tests:** generated test matrix of (role × permission-guarded endpoint) from the RBAC catalog in `../product/Personas.md` §1; every cell asserted allow/deny; unmapped endpoints fail the build.
- [ ] **Tenant isolation tests:** RLS repository tests (§3), API-level cross-tenant probes, RAG retrieval isolation (§8), artifact storage prefix isolation in MinIO, Kafka consumer filtering.
- [ ] **Secret exposure scans:** gitleaks on every PR (source + WireMock recordings + simulation packs); runtime tests assert secrets are masked in UI payloads, logs, and error responses; audit records exist for every secret read.
- [ ] **Dependency and image scanning:** OWASP Dependency-Check / `gradle dependencyCheckAnalyze` + `pnpm audit` on PR; Trivy scan of all built images; criticals block release, highs require documented waiver.
- [ ] **Prompt injection test cases for RAG/MCP:** corpus of adversarial documents ("ignore previous instructions", tool-invocation lures, data-exfiltration prompts, cross-tenant reference bait) ingested into RAG; tests assert agents do not execute injected instructions, do not call non-allow-listed MCP capabilities, and the Validation Agent flags contaminated outputs.
- [ ] **AuthN edge cases:** expired/blank/foreign-issuer tokens, local-account fallback lockout, OIDC clock skew.
- [ ] **Anti-surveillance guard:** static test asserting no API or export surfaces per-individual ranked productivity lists (team-level grain enforced by metric registry tests).

## 13. Test data management

- **Seeded simulation packs** in `/simulation/packs/<name>` with a manifest (seed, tenants, teams, date range, event counts). The canonical catalog is `/simulation/packs/{demo-small, demo-midsize, demo-troubled, enterprise-large}`. Packs are versioned; golden datasets (§7) pin exact pack versions. `/simulation/packs/demo-small` boots in CI (smoke); `/simulation/packs/enterprise-large` feeds nightly performance and load runs.
- **Anonymized fixtures policy:** any fixture derived from real systems (e.g., WireMock recordings) must pass the scrub script and a reviewer checklist (no names, emails, hostnames, ticket text, or tokens) before commit; provenance is recorded in the fixture's README. Real customer data never enters the repo, CI, or developer machines. Synthetic data is always preferred over anonymized data.
- Dataset builders (§3) are the only sanctioned way to create entities in integration tests; direct inserts bypass RLS/audit and are rejected in review.

## 14. CI pipeline test stages

| Stage | On PR | Nightly | Contents |
|---|---|---|---|
| Static | ✔ | ✔ | Compile, Checkstyle/ErrorProne, ESLint/tsc, gitleaks, license check |
| Unit | ✔ | ✔ | Backend JUnit 5 + frontend Vitest, coverage gates |
| Architecture & contract | ✔ | ✔ | Modulith verify, ArchUnit, event schema validation, OpenAPI diff, connector kit |
| Integration | ✔ | ✔ | Testcontainers suites (Postgres+pgvector, Kafka, Redis, MinIO, mock OIDC) |
| E2E smoke | ✔ (E1, E3, E7) | ✔ | Compose stack + Playwright subset |
| E2E full | — | ✔ | All §10 scenarios, all persona journeys, axe smoke |
| AI evals | schema/fake-LLM subset ✔ | ✔ | Full eval harness, Validation Agent regression suite; optional local-model job |
| Performance | — | ✔ | Gatling §11 with baseline comparison |
| Security deep | fast subset ✔ | ✔ | Full authz matrix, prompt injection corpus, Trivy, dependency scan |
| Recording drift | — | ✔ (non-blocking, network-permitting) | Live sandbox replay comparison |

PR wall-clock budget: ≤ 20 minutes. Anything slower moves to nightly with a smoke representative on PR.

## 15. Flaky test policy

- A test that fails then passes on retry is auto-labeled flaky by CI and filed as a `flaky-test` issue against the owning stream within 24 h.
- Flaky tests are quarantined (`@Tag("quarantine")`, excluded from merge gates but still run and reported) for a maximum of 5 working days; after that the owning stream either fixes or deletes-and-reimplements it. Quarantine count > 10 repo-wide freezes non-fix merges for the owning module.
- No blanket retries: CI retries at most once, only to gather flakiness evidence; the retry never turns a red PR green for merge purposes on `main`.
- Root-cause categories are tracked (timing, container startup, shared state, ordering) and reported monthly; recurring categories trigger harness fixes, not per-test patches.

## 16. Quality gates for merge

A PR merges to `main` only when all of the following hold:

- [ ] All PR-stage CI checks (§14) green, including coverage thresholds (§1) and Modulith/ArchUnit verification (§4).
- [ ] OpenAPI diff clean or explicitly approved breaking change with ADR reference (§5.2).
- [ ] New/changed event types carry JSON Schemas and compatibility tests (§5.1).
- [ ] New connector or connector change passes the full contract test kit K1–K7 (§5.3).
- [ ] New/changed metric has a golden dataset case (§7) and a complete metric definition (purpose, formula, inputs, grain, caveats/limitations, gaming risks).
- [ ] New/changed agent has output schema validation and eval coverage (§8).
- [ ] No quarantined test introduced by this PR; no gitleaks or critical dependency findings.
- [ ] At least one review from the owning stream (see team topology in `../implementation/PhaseBasedImplementationPlan.md` §3); docs under `/docs` updated in the same PR when behavior changes (docs-as-code).

Release gates additionally require: full nightly suite green on the release candidate, performance baselines met (§11), security checklist (§12) fully checked, and the E2E suite (§10) passing against both Docker Compose and the Kubernetes reference deployment (Phase 5 onward).

## 17. Local developer workflow

The suites a developer runs locally mirror the CI stages exactly (same Gradle/pnpm tasks, same containers), so "green locally, red in CI" is treated as a harness bug.

| Command | Runs | When to run |
|---|---|---|
| `./gradlew test` | Backend unit tests + coverage | Before every push |
| `./gradlew integrationTest` | Testcontainers suites for changed modules | Before pushing changes touching persistence, Kafka, cache, storage, or auth |
| `./gradlew check` | Units + Modulith verify + ArchUnit + schema/OpenAPI contract checks | Before every push |
| `./gradlew connectorKit --tests '*<Connector>*'` | Contract kit K1–K7 for one connector | Any connector change |
| `./gradlew goldenTest` | Golden dataset replay (§7) | Any metric engine or normalizer change |
| `pnpm test` | Vitest + coverage | Before every frontend push |
| `pnpm e2e:smoke` | Playwright E1, E3, E7 against the `make dev-up` stack | Before pushing cross-cutting changes |
| `scripts/run-eval-harness --fake` | AI evals with FakeLlmProvider | Any agent, prompt, or RAG change |
| `scripts/scrub-recordings` | Sanitize newly captured WireMock recordings | Before committing any recording |

Pre-push expectation: `./gradlew check` (or `pnpm test` for frontend-only changes) locally; everything heavier is CI's job. The Compose dev stack (`make dev-up`) is the only supported way to run E2E locally — no bespoke local setups, so failures reproduce identically everywhere.

## 18. Mutation and property-based testing

Line coverage proves execution, not verification. Two supplements target the highest-consequence logic:

- **Mutation testing (PIT)** runs nightly on `eip-analytics` formula packages and `eip-core` invariants (envelope handling, ExternalRef identity, WorkItem state machine). Target mutation score ≥ 70% on those packages; the score is reported per PR touching them (informational on PR, gating nightly). A surviving mutant in a metric formula means a golden case is missing — fix the golden set (§7), not just the unit test.
- **Property-based testing (jqwik)** covers algebraic properties that example-based tests under-sample:
  - Checkpoint resume: for any split point of an event stream, sync(prefix) + resume(suffix) ≡ sync(whole) in canonical state.
  - Idempotency: consuming any permutation-with-duplicates of an event set (respecting per-key order) yields the same final state.
  - Cursor pagination: concatenated pages ≡ unpaginated result, no overlaps or gaps, for any page size.
  - Percentile/statistics functions: invariance under input order, monotonicity, documented interpolation behavior at edges.
  - Envelope serialization: serialize→deserialize round-trip identity for generated envelopes across all `schemaVersion`s.

## 19. Test observability and reporting

- CI publishes per-stage JUnit/Playwright/Gatling reports; trends (duration, failure rate, flakiness, coverage, mutation score) land in the self-observability Grafana stack (`/infra/grafana`) — the platform's own dashboards eat this dogfood from Phase 2 onward via the Generic CI/CD connector (see `../implementation/PhaseBasedImplementationPlan.md` §11).
- Every E2E failure uploads the Playwright trace, the Compose logs bundle, and the seeded pack manifest, so failures are diagnosable without rerunning.
- Test-suite wall-clock is budgeted per stage (§14); a stage exceeding its budget for 3 consecutive days is treated as a defect owned by DevX/infra.
- Ownership: each stream (see `../implementation/PhaseBasedImplementationPlan.md` §3) owns the health of tests in its modules — including quarantine debt (§15) and coverage ratchets (§1). Cross-cutting harness code (kit, builders infrastructure, eval harness runner, E2E fixtures) is owned by DevX/infra with stream contributions.

## 20. Acceptance criteria for this strategy

This strategy is itself verifiable. It is correctly implemented when:

- **Given** a clean checkout and a running Docker daemon, **when** a developer runs `./gradlew check integrationTest`, **then** the full PR-equivalent backend suite passes with no network access beyond the local containers.
- **Given** a new connector implementation, **when** it does not subclass `ConnectorContractTestKit`, **then** an ArchUnit rule fails the build with a message pointing to §5.3.
- **Given** a PR that changes a metric formula, **when** no golden dataset case changes with it, **then** either the golden suite fails (behavior changed) or review rejects it (dead change) — there is no path to silently altering a metric.
- **Given** a PR that removes an OpenAPI path or narrows an event schema, **when** CI runs, **then** the contract stage fails unless the PR carries the documented breaking-change approval.
- **Given** any test writing data for `TENANT_A`, **when** the same suite queries as `TENANT_B`, **then** zero rows, chunks, artifacts, or events are visible — in every layer from repository test to E7.
- **Given** the air-gapped CI profile, **when** the full nightly suite runs, **then** every stage except the explicitly network-permitting recording-drift job completes successfully.
- **Given** a flaky test, **when** it has been quarantined for more than 5 working days, **then** CI reports it as a policy violation against the owning stream (§15).
