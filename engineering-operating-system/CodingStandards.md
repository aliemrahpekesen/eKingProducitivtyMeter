# Coding Standards

Who reads this, when: every engineering role that writes or changes code — R-IE, R-TE, R-RE before touching a file; R-CR at every G8 review. This document turns the language-level conventions already decided in [BackendPlan](../docs/engineering/BackendPlan.md), [FrontendPlan](../docs/engineering/FrontendPlan.md), [DatabasePlan](../docs/engineering/DatabasePlan.md), and [TestingStrategy](../docs/testing/TestingStrategy.md) into normative, checkable rules. Those /docs plans are the source of record; if this document ever disagrees with them, follow /docs and fix this file per docs-first ([DocumentationStandards](./DocumentationStandards.md)). Keywords MUST/SHOULD/MAY are used per RFC 2119 (declared once in [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md)).

## 1. Enforcement map

Every rule below is enforced by a named tool at a named gate. Formatting and static analysis are not review topics — they are machine verdicts at G1.

| Area | Tool (the enforcement mechanism) | Gate |
|---|---|---|
| Java formatting | Spotless + Google Java Format (`spotlessCheck`) — [BackendPlan §4](../docs/engineering/BackendPlan.md) | G1 |
| Java static analysis | Error Prone + NullAway, `-Werror` (zero warnings) | G1 |
| Module boundaries | Spring Modulith `ModularityTests` + ArchUnit rules — [TestingStrategy §4](../docs/testing/TestingStrategy.md) | G1 |
| TypeScript formatting/lint | Prettier check + ESLint with `--max-warnings 0` | G1 |
| TypeScript types | `tsc --noEmit`, `strict: true` — [FrontendPlan §1](../docs/engineering/FrontendPlan.md) | G1 |
| OpenAPI surface | springdoc generation + openapi-diff vs `main` — [TestingStrategy §5.2](../docs/testing/TestingStrategy.md) | G1 |
| Test conventions, coverage ratchets | JUnit 5/Vitest + JaCoCo/Vitest coverage — [TestingStrategy §1](../docs/testing/TestingStrategy.md) | G2 |
| Secrets/PII in code, fixtures, logs | gitleaks + log-redaction tests — [SecurityModel §6](../docs/architecture/SecurityModel.md) | G3 |
| Everything not tool-checkable | R-CR review; MUST violations are MAJOR, SHOULD violations MINOR ([CodeReviewChecklist](./CodeReviewChecklist.md)) | G8 |

## 2. Java 21 standards (backend)

Grounded in [BackendPlan §2–§4](../docs/engineering/BackendPlan.md). The feature policy table in BackendPlan §4 is binding; the rules below restate it as MUST/MUST NOT for review use.

### 2.1 Language features

| Rule | Detail |
|---|---|
| Records MUST be used | for DTOs, `@ConfigurationProperties`, event payloads, value objects. JPA entities remain classes. |
| Sealed hierarchies MUST be used | for every closed set: event families (`sealed interface DomainEvent permits WorkItemEvent, ScmEvent, CicdEvent, QualityEvent, OpsEvent`), the exception taxonomy (§2.4), connector sync results, agent run states. Adding a permitted subtype is a contract-visible change — declare it in the task's docs impact. |
| Pattern matching MUST replace | visitor patterns and `instanceof` chains in event handling and normalizer dispatch; `switch` over sealed types MUST be exhaustive (no `default` arm that hides a missing case). |
| Virtual threads MUST carry | all blocking connector IO, via per-connector `Executors.newVirtualThreadPerTaskExecutor()` bounded by Resilience4j bulkheads (BackendPlan §7). No reactive stack. |
| Text blocks MUST be used | for JSON Schema literals, `JdbcClient` SQL, and prompt templates. |
| Nullability | JSpecify annotations (`@NullMarked` at package level); `Optional` only as a return type — never a field or parameter. NullAway enforces at G1. |
| Time and IDs | `Clock` injected everywhere; entity IDs are UUIDv7 from the `eip-core` generator. `java.util.Date` and `java.time.LocalDateTime` in domain code are forbidden (use `Instant`/zoned; ArchUnit-enforced). |
| Forbidden | field/setter injection, checked-exception tunneling, `synchronized` around IO on virtual threads (pinning — use `ReentrantLock`), `KafkaTemplate` outside designated event publishers, LangChain4j imports outside `eip-ai`. |

### 2.2 Spring conventions

- Constructor injection ONLY; `@Autowired` omitted (implicit single constructor). Beans are `final`-field classes, or records where stateless. Field injection fails G8 as MAJOR.
- Configuration MUST be validated `@ConfigurationProperties` records with configuration-processor metadata. `@Value` in production code is forbidden ([BackendPlan §2.2](../docs/engineering/BackendPlan.md)).
- Exactly three runtime profiles exist: `local`, `demo`, `prod`, plus the worker role profiles of BackendPlan §9. Introducing any other profile is a CC-1-adjacent decision requiring an ADR ([ADRProcess](./ADRProcess.md)).
- `@Transactional` MUST NOT appear on controllers; transactions live in application services. Layering per module: application service → domain → infrastructure; controllers exist only in `eip-app`.
- Mapping is explicit static factory methods on DTO records — MapStruct (or any mapping framework) is forbidden (BackendPlan §2.4).

### 2.3 Package-by-module (Spring Modulith)

- Package roots follow BackendPlan §1 exactly: `com.eip.<module-short-name>.<area>` (e.g. `com.eip.analytics.query`, `com.eip.tenancy.rbac`). A new top-level package under a module updates that module's `MODULE.md` in the same PR.
- Every module has `package-info.java` declaring allowed dependencies and named interfaces; the allowed-dependency matrix is [ArchitectureOverview §5](../docs/architecture/ArchitectureOverview.md) — editing it is CC-1 (G4, R-CA).
- Cross-module calls go through the exposing module's named interface (its `api` package) or Kafka events — never repositories, entities, or internals of another module. `ModularityTests` red = build red, not a review comment.

### 2.4 Exception design

The sealed taxonomy in [BackendPlan §10](../docs/engineering/BackendPlan.md) is the only permitted exception surface toward the API:

- Every exception crossing a module API boundary MUST extend sealed `EipException` (`com.eip.core.error`) via one of its documented leaves (`ValidationException`, `PermissionDeniedException`, `ConnectorException` and its sealed leaves, `LlmProviderException`, …).
- Adding a leaf or a problem `type` URI changes the RFC 7807 surface: update BackendPlan §10 and [APIDesign §7](../docs/engineering/APIDesign.md) in the same PR (G7) and declare CC-1 if the `/api/v1` contract changes.
- `ResourceNotFoundException` MUST never leak cross-tenant existence; `InternalException` responses carry `traceId` and generic detail only — no stack traces to clients.
- Catch-and-rethrow that discards the cause, and `catch (Exception e) {}` swallows, are MAJOR review findings.

## 3. SQL and Flyway standards

Grounded in [DatabasePlan §1, §5, §7](../docs/engineering/DatabasePlan.md).

- Naming MUST follow DatabasePlan §1: `snake_case` everywhere; tables singular-noun (`work_item`); indexes `ix_<table>_<cols>`, unique `ux_`, FKs `fk_<table>_<ref>`, checks `ck_`.
- Migrations: `V<seq>__<snake_case_summary>.sql` (monotonic, unpadded, per the DatabasePlan §7 examples `V1__baseline.sql`, `V2__add_release_readiness_score.sql`); repeatables `R__<object>.sql` for views, RLS policies, functions. Location: `backend/eip-app/src/main/resources/db/migration`.
- One-way only. No undo migrations (a CI test asserts none exist — [TestingStrategy §3.1](../docs/testing/TestingStrategy.md)). Rollback = a new forward corrective migration.
- No destructive change without expand–contract: any shape-breaking change (drop/rename column, type narrowing, NOT NULL on existing data) MUST follow DatabasePlan §7's expand → dual-read → batched backfill → contract sequence, with the contract migration at least one release later. All schema changes are CC-4 → G4 review by R-DBA.
- Every new tenant-scoped table MUST carry `tenant_id uuid NOT NULL` and the standard RLS policy pair via `R__rls_policies.sql`, verified by an RLS `@DataJpaTest` (cross-tenant read returns zero rows). The GUC is `app.tenant_id`, transaction-scoped `SET LOCAL` — never any other name or scope (DatabasePlan §5).
- Enums are `text` + `CHECK` constraints, never native Postgres enums. Backfills > ~10⁶ rows run in batched maintenance jobs, never inside the migration.
- No new index without a named query pattern added to DatabasePlan §4 and `EXPLAIN (ANALYZE, BUFFERS)` evidence in the PR; hot-path query changes are CC-3 → G5 (R-PE).
- Analytics SQL is hand-written in text blocks via `JdbcClient` with named parameters only — string concatenation of user input into SQL is a BLOCKER (BackendPlan §5).

## 4. TypeScript / React standards (frontend)

Grounded in [FrontendPlan §1, §5–§9, §12](../docs/engineering/FrontendPlan.md).

- `strict: true` is non-negotiable. Forbidden: `any` (explicit or via `as any`), `@ts-ignore` (use `@ts-expect-error` with a `TASK-NNNN`/`DEBT-NNN` reference), non-null assertions `!` outside test code.
- API types come ONLY from the generated OpenAPI client in `src/api/generated`; hand-written request/response types are a MAJOR finding. New endpoints are consumed only after OpenAPI regeneration (FrontendPlan §14).
- Server state lives in TanStack Query only (FrontendPlan §7): query keys built by the central `qk` factory as `[area, resource, params]`; mutations invalidate by key prefix; API data is never copied into other stores; derived data is computed in memos, never cached manually. Introducing Redux/Zustand or any client cache requires an ADR (FrontendPlan §7 rule 2).
- Components: feature folders under `src/features/*` own their routes, components, query hooks, and messages; cross-feature imports only via `/design`, `/api`, `/app`, `/forms` (FrontendPlan §12). Files: components `PascalCase.tsx` (screens suffixed `Screen`), hooks `useThing.ts`, everything else camelCase. Component-scoped CSS Modules; no runtime CSS-in-JS. Tables use `EipDataTable`, charts use `EipChart` — no direct ECharts instantiation in features.
- Routes declare `requiredPermission` from `x-eip-permission`; mutating controls sit behind `<Can>` gates; SSE consumption goes through the `useSse` hook with polling fallback — no raw `EventSource` in feature code (FrontendPlan §2, §5).
- Error/loading/empty states follow FrontendPlan §9 exactly: problem+json mapped through the single `ApiError` type, `traceId` surfaced, both empty-state variants implemented.
- i18n: ALL user-visible strings externalized via react-i18next with ICU messages; source locale `en-US`; keys per feature folder; no string concatenation of translatable fragments; locale-aware dates/numbers via `Intl` (FrontendPlan §8). A hard-coded UI string is a MAJOR finding at G8.

## 5. Test code standards

Grounded in [TestingStrategy §2–§3, §13, §15](../docs/testing/TestingStrategy.md).

- Naming: `methodUnderTest_condition_expectedOutcome`, or BDD `@DisplayName` in given/when/then form. Test bodies follow given/when/then structure (blank-line or comment separated); one logical assertion cluster per test.
- Assertions: AssertJ only (backend); no Hamcrest, no bare `assertEquals`. Frontend: Testing Library queries by role/label, never implementation details.
- No sleeps. `Thread.sleep`/`setTimeout`-based waiting is a BLOCKER. Use pinned `Clock.fixed(...)`, condition polling with bounded timeout (Awaitility), Testcontainers wait strategies, or TanStack Query test utilities.
- No mocking of owned types where avoidable: real objects or hand-rolled fakes in `testFixtures` (`InMemoryCheckpointStore`, `FakeLlmProvider`); Mockito only for third-party seams (TestingStrategy §2).
- Unit tests MUST NOT boot Spring (`@SpringBootTest` in a unit test is rejected in review); integration tests carry `@Tag("integration")` and use the singleton Testcontainers set per module (Postgres 16 + pgvector, Kafka KRaft, Redis 7, MinIO, mock OIDC).
- Entities in integration tests are created ONLY via the dataset builders; direct inserts bypass RLS/audit and are rejected (TestingStrategy §13). Every repository test class includes the `TENANT_A` write / `TENANT_B` invisibility probe.
- Determinism: no real SaaS APIs, no real LLMs, seeded randomness, injectable `EventIdGenerator`. `testcontainers.reuse` is forbidden in CI.
- Regression rule: every bug-fix PR contains the failing test in a commit BEFORE the fix commit (red→green evidence — see [DevelopmentLifecycle](./DevelopmentLifecycle.md)).
- Flaky handling per TestingStrategy §15: quarantine ≤ 5 working days; a PR MUST NOT introduce a quarantined test.

## 6. Logging standards

Grounded in [ObservabilityModel §5](../docs/architecture/ObservabilityModel.md) and [SecurityModel §6–§7](../docs/architecture/SecurityModel.md).

- Structured JSON on stdout in `demo`/`prod` (Logback + logstash encoder); human-readable only in `local`. Every line carries `tenantId`, `traceId`, `spanId` from MDC, plus `module` and a snake_case machine-readable `event` key per the ObservabilityModel §5 schema. Additional context keys are bounded and allow-listed per event.
- Never logged (redaction filter enforced, CI-tested): secrets or token-shaped values (`[REDACTED]`), prompt/completion bodies, Member names/emails (ids or pseudonyms only), raw `raw_*` payloads (log digests + sizes). Violations are CC-2 territory: G3 + R-SA.
- No `System.out`/`console.log` in production code; no string-concatenated log messages where structured fields belong; log levels `DEBUG|INFO|WARN|ERROR` only (no `TRACE` in production profiles).
- Micrometer metric names follow `eip.<module>.<thing>` with the mandatory `tenant` tag (BackendPlan §12), exported as Prometheus `eip_*` per the [ObservabilityModel §3](../docs/architecture/ObservabilityModel.md) catalog; new metrics for new endpoints/consumers/jobs are a G6 requirement, not optional polish.

## 7. Comment policy

Comments state constraints, not narration.

- A comment MUST answer "why / what invariant holds / what breaks if you change this" (ordering guarantees, RLS assumptions, unit conventions, protocol quirks). Comments that restate what the code does are removed at review.
- Public module API interfaces (Modulith named interfaces, published SPIs) MUST carry Javadoc/TSDoc describing contract semantics: idempotency, tenancy expectations, error taxonomy leaves thrown.
- `TODO` MUST reference a register entry: `// TODO(DEBT-NNN): ...` or `// TODO(TASK-NNNN): ...`. An unreferenced TODO violates register-or-fix ([TechnicalDebtPolicy](./TechnicalDebtPolicy.md)) and is a MAJOR finding.
- No commented-out code in merged PRs. No session narration ("tried X, didn't work") — that belongs in the handoff note.

## 8. Formatting

Formatting is fully delegated to tools; style debates are out of scope by construction.

- Java: Spotless with Google Java Format; the `buildSrc` convention plugin applies it to every module. `spotlessCheck` failure fails G1.
- TypeScript: Prettier (repo-root config, no per-package overrides) + ESLint; `pnpm` is the only package manager (pinned lockfile).
- SQL in migrations: uppercase keywords, one column per line in DDL, matching the DatabasePlan §3 representative DDL style.
- Generated code (`src/api/generated`, Modulith Documenter output, `openapi.json`) is never hand-edited and never counted against PR size targets ([RepositoryRules](./RepositoryRules.md)).

## 9. Author self-check before opening a PR

Run before requesting G8 review; R-CR applies the same list. Grouped by change class ([QualityGatePolicy §3](./QualityGatePolicy.md)).

**Every PR (CC-7 baseline)**

- [ ] `./gradlew check` and/or `pnpm test` green locally (same tasks as CI — [TestingStrategy §17](../docs/testing/TestingStrategy.md)); no new warnings anywhere.
- [ ] No `any`/`@ts-ignore`/field injection/`@Value`/`Thread.sleep`/`System.out`/hard-coded UI strings introduced.
- [ ] New code sits in the correct module/package family (§2.3, §4); no cross-feature or cross-module internal imports.
- [ ] Comments are constraints-only; every TODO carries a `DEBT-NNN`/`TASK-NNNN` reference.
- [ ] Logs structured, redaction-safe, with `tenantId`/`traceId`; new external calls and jobs carry metrics + spans (feeds G6).

**CC-4 (schema/migration) additionally**

- [ ] Migration named `V<seq>__<snake_case_summary>.sql`; forward-only; expand–contract for any shape change.
- [ ] New tenant-scoped tables have `tenant_id` + RLS policy + RLS test; enums are text + CHECK.
- [ ] New indexes have a named query pattern + `EXPLAIN (ANALYZE, BUFFERS)` evidence in the PR.

**CC-1 (contract anchors) additionally**

- [ ] Sealed-hierarchy additions, envelope/topic/SPI changes declared in the PR's contract-impact field; anchor docs updated in the same PR; ADR if decision-level ([ADRProcess](./ADRProcess.md)).
- [ ] OpenAPI regenerated; generated TS client compiles; operationIds unchanged unless the change is declared breaking.

**CC-2 (security-relevant) additionally**

- [ ] No secrets/PII path touched without the [SecurityChecklist](./SecurityChecklist.md); secret fields declared `"format": "eip-secret"`; audit events registered for new mutating capabilities.

## Related documents

- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — gate definitions G0–G8, RFC 2119 note
- [ArchitecturePrinciples.md](./ArchitecturePrinciples.md) — the standing invariants these standards serve
- [CodeReviewChecklist.md](./CodeReviewChecklist.md) · [TestingChecklist.md](./TestingChecklist.md) · [SecurityChecklist.md](./SecurityChecklist.md) — gate-time checklists
- [QualityGatePolicy.md](./QualityGatePolicy.md) · [DevelopmentLifecycle.md](./DevelopmentLifecycle.md) · [RepositoryRules.md](./RepositoryRules.md)
- [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md) · [DependencyManagement.md](./DependencyManagement.md) · [DocumentationStandards.md](./DocumentationStandards.md)
- Spec of record: [BackendPlan](../docs/engineering/BackendPlan.md) · [FrontendPlan](../docs/engineering/FrontendPlan.md) · [DatabasePlan](../docs/engineering/DatabasePlan.md) · [APIDesign](../docs/engineering/APIDesign.md) · [TestingStrategy](../docs/testing/TestingStrategy.md) · [ObservabilityModel](../docs/architecture/ObservabilityModel.md) · [SecurityModel](../docs/architecture/SecurityModel.md)
