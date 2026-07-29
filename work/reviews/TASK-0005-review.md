# TASK-0005 — R-CR Review (with R-CA CC-1 architecture approval)

- **Task:** [../tasks/TASK-0005.md](../tasks/TASK-0005.md) · story P0-E2-S1 · **change class CC-1** (shared-kernel contract anchor)
- **Branch:** `feature/TASK-0005-eip-core-skeleton` · **base:** `integration/SPRINT-00` · **commit under review:** `ef4a817`
- **Reviewers:** R-CR (independent) + **R-CA** (G4 contract-anchor / CC-1 second approval) · **Date:** 2026-07-07
- **Method:** diff + task spec + cited docs only ([DomainModel §1–§2](../../docs/architecture/DomainModel.md), [BackendPlan §1](../../docs/engineering/BackendPlan.md), [CodingStandards](../../engineering-operating-system/CodingStandards.md), [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md), [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md), [TaskSpecs TASK-0005](../../sprints/sprint-00/TaskSpecs.md), [approach approval](../approvals/TASK-0005-approach.md)). Every validation **independently re-run** — author prose was not taken as evidence (CI-is-truth).

## Overall verdict: **APPROVED**

0 BLOCKER · 0 MAJOR · 0 MINOR · 3 NIT (non-blocking). CC-1 two-approval requirement met (R-CR + R-CA below).

The shared kernel is a faithful, standards-clean realization of the approved approach: types only, leaf-enforced three ways, 100% covered, and every CC-1 condition C1–C6 satisfied. No contract-anchor deviation from DomainModel §1–§2 → no ADR required (as the approach predicted).

## Validation command status (independently re-run this session)

| Command | Result |
|---|---|
| `./gradlew :eip-core:clean :eip-core:check` | **BUILD SUCCESSFUL** — compile (`-Werror`, Error Prone + NullAway), `spotlessJavaCheck`, `checkstyleMain`, `checkstyleTest`, `test`, `jacocoTestReport`, **`jacocoTestCoverageVerification`**, `check` all green |
| `./gradlew :eip-core:test --rerun-tasks` (no cache) | **BUILD SUCCESSFUL** — **33 tests, 0 failures, 0 errors, 0 skipped** across 9 test classes |
| `ApplicationModules.of("com.eip.core").verify()` | **PASS** (`ModularityTests.verifiesModuleStructure`); leaf sub-graph `events → domain` (exposed types only), no `eip-core → eip-<other>` edge |
| AC-5 leaf-violation reproduction | Independently reproduced with a **different** module: a throwaway class importing `com.eip.analytics.metrics.MetricEngine` → `error: package com.eip.analytics.metrics does not exist` → **BUILD FAILED**; file removed → **BUILD SUCCESSFUL**; tree clean (no probe committed) |

## Coverage result

JaCoCo **Total: 0 of 607 instructions missed = 100%**, **0 of 14 branches missed = 100%**. Ratchet for `eip-core` is ≥ 85% line / ≥ 75% branch ([TestingStrategy §1](../../docs/testing/TestingStrategy.md)) → **met with margin**; `jacocoTestCoverageVerification` passed in the clean run.

## Acceptance criteria status

| AC | Status | Evidence |
|---|---|---|
| AC-1 — `verify()` green; leaf | ✅ | `ModularityTests.verifiesModuleStructure` green; `LeafBoundaryTest` (ArchUnit) green; Gradle module isolation confirmed |
| AC-2 — records/sealed/enum; UUIDv7 + UTC unit test | ✅ | `WorkItemType`/`EntityType` enums, `ExternalRef`/`EventEnvelope`/`SchemaVersion` records, `EipException` sealed; `DefaultUuidV7GeneratorTest` proves v7 + IETF variant + clock timestamp + intra-ms monotonicity/overflow + determinism. UTC is by construction (`java.time.Instant` only; no `Date`/`LocalDateTime`) |
| AC-3 — `externalId`≠`externalKey`; 11-field envelope | ✅ | `ExternalRef`: `externalId` required/immutable-documented (AD-14) vs `@Nullable externalKey` mutable + `keyAliases`; `EventEnvelope` has exactly 11 components in canonical order (verified in source and by `getRecordComponents().hasSize(11)`) |
| AC-4 — MODULE.md accurate | ✅ | `backend/eip-core/MODULE.md` — purpose, exported-types table, invariants (leaf/tenancy/identity/conventions), owned tables/topics = none, generated-docs pointer |
| AC-5 — leaf violation blocks build | ✅ | Reproduced independently (see above); no broken code committed |

## R-CR review findings

**Correctness / standards (independently verified against the diff):**

- `ExternalRef` — all 11 §2.2 attributes present (`id, tenantId, entityType, entityId, sourceSystem, sourceInstance, externalId, externalKey, keyAliases, url, lastSeenAt`); `externalId` non-blank/required, `externalKey` `@Nullable`, `keyAliases` defensively copied via `List.copyOf` (immutable, null-element-rejecting). Construction invariants use `NPE`/`IllegalArgumentException` (correct for value-object invariants — not the API-boundary `EipException`, avoiding a domain→error coupling).
- `EventEnvelope` — exactly the canonical 11 fields; 10 required + `@Nullable traceparent` (a sound implementation refinement — W3C trace context is legitimately absent; field name/count unchanged, so no anchor deviation).
- `SchemaVersion` — `major ≥ 1`, `minor ≥ 0` enforced; `parse` rejects wrong arity (`"1"`, `"1.2.3"`), non-numeric (`"x.y"`), and negatives (via the range check); `toString` round-trips. Behavior correct.
- `DefaultUuidV7Generator` — RFC 9562 v7 layout correct: 48-bit ms timestamp, version nibble `0x7`, 12-bit `rand_a` monotonic counter, IETF variant (`bit63=1,bit62=0`), 62-bit `rand_b`. Monotonicity holds within a ms, across ms, on 4096-slot overflow (carry), and under a backwards clock; guarded by `ReentrantLock` (pin-safe — **not** `synchronized`). `Clock` injected; both constructors null-checked.
- `EipException` — `abstract sealed` extends `RuntimeException`, permits exactly the four **final** kernel leaves (`ValidationException`, `ResourceNotFoundException`, `PermissionDeniedException`, `InternalException`), each carrying `(message)` and `(message, cause)`. Matches CodingStandards §2.4.
- SPI roots `secrets`/`storage`/`vector` contain **only** `package-info.java` — no interfaces, no impls.
- No persistence (no `jakarta.persistence`/`@Entity`/`@Repository`/`JdbcClient`/Flyway/DDL), no Kafka (no `KafkaTemplate`/`spring-kafka`), no Spring stereotypes in `main`, no `java.util.Date`/`LocalDateTime`, no `@Autowired`/`@Value`, no `System.out`, no unreferenced `TODO`, no commented-out code.
- `@SuppressWarnings("NullAway")` appears only on the four deliberate null-contract tests, each with a justifying comment — legitimate.

**Scope / process:**

- `/docs` product-spec baseline **untouched** (commit touches no `docs/` path — verified).
- Write-set: every path is under `backend/eip-core/` or one of the three disclosed reconciliation files. Single-writer holds (no other in-flight L1/L3 task touches `/backend/gradle` or `/backend/eip-core`).
- Commit message conventional, `[TASK-0005]` tagged, sensible size (generated `.puml` + tests excluded from the size target per [RepositoryRules](../../engineering-operating-system/RepositoryRules.md)).

**NITs (non-blocking, no fix required to merge):**

- **NIT-1** — the approach fixed the write-set at "`/backend/eip-core` only", but two dependency versions (`jspecify`, `assertj`) landed in `/backend/gradle/libs.versions.toml`. This is the correct home for versions ([DependencyManagement](../../engineering-operating-system/DependencyManagement.md)) and is disclosed in the task file + sprint log with a single-writer justification — **accepted as a docs-first reconciliation**, consistent with the TASK-0001 precedent.
- **NIT-2** — `spring-modulith-docs` is declared as a raw Gradle coordinate string (BOM-versioned) rather than a version-catalog alias, unlike the other modulith artifacts. Consider a `libs` alias for consistency when DEBT-001 (catalog wiring) is paid.
- **NIT-3** — `EntityType` seeds 27 canonical names spanning contexts beyond DomainModel §1–§2 (SCM/CI-CD/Ops). All are grounded canonical entities and enum additions are additive/non-breaking, so this is safe; just flagging that the set will be reconciled against the full DomainModel as those contexts’ tasks land.

## R-CA architecture approval finding (G4 / CC-1)

**APPROVED.** As the contract-anchor owner I confirm:

- The shared kernel matches [DomainModel §1–§2](../../docs/architecture/DomainModel.md) (canonical-over-raw, tenant-by-construction, `WorkItem` supertype, UUIDv7 identity, `ExternalRef` provenance with AD-14 `externalId`/`externalKey` split) and [BackendPlan §1](../../docs/engineering/BackendPlan.md) (`eip-core` leaf; packages `domain`/`events`/`error` + SPI roots `secrets`/`storage`/`vector`).
- The **event envelope carries exactly the canonical 11 fields** in order — no drift (AP-3). No field-name/semantics deviation from DomainModel §6 → **no ADR triggered** (matches approach risk R2 expectation).
- Leaf invariant (AP-1) enforced defence-in-depth (Gradle isolation + Modulith `verify()` + ArchUnit). Tenant-scoping present on `ExternalRef`/`EventEnvelope` (AP-2); no individual-ranking/activity type introduced (AP-8/NFR-071).
- `EipException` sealing confined to kernel leaves; cross-module leaf strategy correctly deferred to a future ADR (condition C1) — no premature or mis-sealed taxonomy.

Owning-architect (R-BA) concern is subsumed by this architecture approval; no design change requested.

## Conditions C1–C6 status (from the approach approval)

| Condition | Status | Note |
|---|---|---|
| C1 — seal over kernel leaves only; cross-module → ADR | ✅ | `EipException` permits exactly the 4 leaves; deferral documented in `error/package-info.java` |
| C2 — SPI package roots only | ✅ | `secrets`/`storage`/`vector` = `package-info.java` only |
| C3 — `WorkItem` discriminator + contract only | ✅ | Only `WorkItemType`; no JPA entity/persistent fields |
| C4 — leaf via ArchUnit + Modulith; AC-5 vs empty module; committed Documenter | ✅ | All present; `generated-docs/*.puml` committed and deterministic |
| C5 — exactly 11 envelope fields; `payload` a marker | ✅ | Verified; `DomainEventPayload` plain marker, sealed family deferred to Phase 1 |
| C6 — no persistence/Kafka/SPI-impl/business logic | ✅ | Confirmed by static scan |

## Evidence checked

- Commit scope `git show --stat ef4a817` (40 files, +1588/−11); no `docs/` path; all within write-set.
- Independent gradle runs (clean `check`; forced-rerun `test`); JaCoCo HTML total; JUnit XML per-class counts.
- Source read of `ExternalRef`, `EventEnvelope`, `SchemaVersion`, `DefaultUuidV7Generator`, `EipException` (+ leaf finality); SPI-root directory listings; forbidden-construct greps.
- AC-5 independent reproduction (distinct module) and clean revert.

## DoD alignment

Code, Tests (coverage ratchet, AC demonstrations), Security (no secrets; no individual-surveillance surface), Docs (MODULE.md; `/docs` untouched), Process (CC-1 declared + confirmed; write-set reconciliation disclosed) — all satisfied at the merge-time layer. **VERIFIED → DONE** remains pending the green CI run on the integration head once the branch is pushed (no remote yet) — the established SPRINT-00 deferral, to be closed then (CI-is-truth).

## Required fixes

**None.** The 3 NITs are optional and need not block merge.

## Merge decision

**APPROVED FOR MERGE** into `integration/SPRINT-00` (local `--no-ff`), pending the user's explicit merge step. CC-1 two-approval satisfied (R-CR + R-CA). Not merged and not pushed in this review step, per instruction.

## Exact next recommended command

```
Merge TASK-0005 into SPRINT-00 integration only. Do not push. Do not create PR. Do not start TASK-0006.
Close the TASK-0005 review (R-CR + R-CA APPROVED, 0 BLOCKER/MAJOR/MINOR, 3 NIT) and commit the review doc
(docs: approve TASK-0005 review). Switch to integration/SPRINT-00; merge feature/TASK-0005-eip-core-skeleton
with --no-ff; mark TASK-0005 MERGED in work/sprints/SPRINT-00.md and set its state log to MERGED; commit the
integration-state update (chore: merge TASK-0005 into sprint 00 integration). Optionally record NIT-2 (catalog
alias for spring-modulith-docs) against DEBT-001. Report the commit hashes, merge status, and the exact next
recommended command (TASK-0006 ADR backfill).
```
