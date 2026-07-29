# TASK-0005 — R-CA Approach Pre-Approval (CC-1)

- **Task:** TASK-0005 · story P0-E2-S1 · **change class CC-1** (shared kernel = contract anchor)
- **Approver:** R-CA (with R-BA) · **Date:** 2026-07-07 · **Gate:** DoR §2 (CC-1 pre-approval before READY → CLAIMED)
- **Spec:** [../../sprints/sprint-00/TaskSpecs.md](../../sprints/sprint-00/TaskSpecs.md) → TASK-0005 · **Approval basis:** [DefinitionOfReady §2](../../engineering-operating-system/DefinitionOfReady.md)

This document records R-CA's pre-approval of the *approach* for TASK-0005 (not the full G4 review, which happens on the PR). It is required because `eip-core` is the shared-kernel contract anchor. **No code is written here** — this is a design gate. Grounded in [DomainModel §1–§2](../../docs/architecture/DomainModel.md), [BackendPlan §1](../../docs/engineering/BackendPlan.md), [ArchitecturePrinciples](../../engineering-operating-system/ArchitecturePrinciples.md), [CodingStandards](../../engineering-operating-system/CodingStandards.md).

## 1. Objective

Establish the `eip-core` shared-kernel **base types** — canonical identity (`UUIDv7`), `ExternalRef` provenance, the `WorkItem` type discriminator, and the domain-event envelope — plus a module-boundary test that keeps `eip-core` a **leaf forever** (AP-1). Skeleton only: no business logic, no persistence, no owned tables. Every downstream module will depend on these types, so getting the contract right now is the point of the CC-1 gate.

## 2. Proposed domain skeleton boundaries

- `eip-core` is and remains a **leaf** ([BackendPlan §1](../../docs/engineering/BackendPlan.md); AP-1): zero outbound dependencies on any other `eip-*` module.
- It defines **types and conventions only** — value objects, enums, the envelope shape, the ID/time conventions, the error-taxonomy root. No repositories, no JPA entities, no Kafka wiring, no SPI implementations.
- Root package `com.eip.core`; sub-packages exactly per BackendPlan §1 (`domain`, `events`, `error`, and the SPI roots `secrets`/`storage`/`vector`). **No new top-level package** is introduced (that would be an AP-1/§5 boundary change requiring its own ADR).

## 3. Modules affected

| Module | Change |
|---|---|
| `eip-core` | New content (types, envelope, ArchUnit/Modulith leaf test, `MODULE.md`, committed Documenter output). Write-set = `/backend/eip-core` only. |
| all other `eip-*` | **None.** The empty skeletons from TASK-0001 are untouched (one is referenced only to *demonstrate* the leaf-violation guard, AC-5). |

## 4. Packages / classes planned (types only)

| Package | Planned types | Notes |
|---|---|---|
| `com.eip.core.domain` | `WorkItemType` (enum: `EPIC,FEATURE,STORY,TASK,BUG,INCIDENT_TICKET`) · `EntityType` (enum of canonical entity names, seeded from DomainModel/FR-034) · `ExternalRef` (record — the 11 §2.2 attributes) · `UuidV7Generator` (interface + `Clock`-injected default impl) · `package-info.java` (`@NullMarked`, allowed-deps Javadoc) | `ExternalRef` is the identity contract: immutable `externalId`, mutable `externalKey`, `keyAliases`, `sourceInstance` (per DomainModel §2.2 / AD-14). |
| `com.eip.core.events` | `EventEnvelope` (record — **exactly** the 11 canonical fields) · `SchemaVersion` (MAJOR.MINOR value type) · `DomainEventPayload` (marker for the payload slot) | Envelope *type* only; topics/Kafka usage are [EventModel](../../docs/engineering/EventModel.md)/Sprint-1 (a forbidden doc this task — the field list comes from DomainModel §1, which lists them identically). |
| `com.eip.core.error` | `EipException` (sealed root) + kernel leaves `ValidationException`, `ResourceNotFoundException`, `PermissionDeniedException`, `InternalException` | Cross-module leaves deferred — see risk R1 / condition C1. |
| `com.eip.core.secrets` / `.storage` / `.vector` | `package-info.java` roots only | SPI interfaces defined by their owning tasks — condition C2. |
| test | `EipCoreModularityTests` (Modulith internal verify) + `LeafBoundaryTest` (ArchUnit: no `com.eip.core` → `com.eip.<other>` import) + unit tests (UUIDv7 ordering, UTC `Instant`, `ExternalRef` id/key distinction, envelope 11-field round-trip) | AC-1/AC-2/AC-3/AC-5. |

Conventions per [CodingStandards §2](../../engineering-operating-system/CodingStandards.md): value objects are `record`s; closed sets are `enum`s (or `sealed` where they carry data); `Clock`-injected time; UUIDv7 from the kernel generator; `java.util.Date`/`LocalDateTime` forbidden in domain code; `@NullMarked` at package level; constructor injection only.

## 5. What WILL be implemented

- `WorkItemType`, `EntityType`, `ExternalRef` (record), `EventEnvelope` (11 fields), `SchemaVersion`, `DomainEventPayload` marker, `UuidV7Generator` + default impl.
- `EipException` sealed root + the four kernel leaves.
- SPI package roots (empty `package-info.java`).
- The leaf-boundary test (ArchUnit + Modulith) + unit tests + committed Modulith Documenter output.
- `/backend/eip-core/MODULE.md` (purpose, exported types, invariants, dependencies = none).

## 6. What will explicitly NOT be implemented

- **No persistence:** no JPA entities, no Flyway, no DDL, no owned tables (Sprint-1 / DatabasePlan — forbidden this task). The persistent `WorkItem` entity (JPA class + full field set) is **P1-E2-S2**; here `WorkItem` exists only as the `WorkItemType` discriminator + the supertype contract documented in `MODULE.md` (condition C3).
- **No Kafka / topic wiring / outbox** (Sprint-1; EventModel is a forbidden doc). Envelope is a plain value type.
- **No SPI interfaces** for secrets/storage/vector — roots only (C2).
- **No concrete domain events** (the sealed `DomainEvent`/payload family) — added when domain entities land (C5).
- **No connector/analytics/AI/reports content**, no business logic.

## 7. Dependency rules

- `eip-core` depends on **nothing** in `com.eip.*` outside itself (AP-1 leaf invariant; BackendPlan §1 "none — leaf"). Third-party deps limited to the JDK + JSpecify annotations (+ test-only JUnit/AssertJ/ArchUnit/Modulith already wired by TASK-0002's `eip.modulith-conventions`).
- No other module gains a dependency in this task.
- Enforcement: G1 — the ArchUnit leaf rule + `ApplicationModules…verify()`; a violating import is a **compile-red event, not a review comment** (AP-1). AC-5 demonstrates it by temporarily importing from a TASK-0001 empty module and showing the build fails.

## 8. Architecture risks

| # | Risk | Assessment / required handling |
|---|---|---|
| R1 | **Sealed `EipException` cannot span Gradle modules.** CodingStandards §2.4 names cross-module leaves (`ConnectorException` in eip-connectors, `LlmProviderException` in eip-ai), but a Java `sealed` type's permitted subclasses must be co-located. | **Condition C1:** seal `EipException` over the **kernel leaves only** now; the cross-module sealing strategy (module-level sealing vs a non-sealed extension contract) is decided by an **ADR when the first cross-module leaf is added** — do not over-build or mis-seal now. |
| R2 | **Envelope drift.** The 11 fields must match the canonical envelope exactly (AP-3). EventModel (the usual home) is a forbidden doc this task. | Field names/semantics come from [DomainModel §1](../../docs/architecture/DomainModel.md) (identical list) and the CLAUDE.md envelope table; **any** deviation is an anchor change → ADR + G4 (docs-first, AP-4). Expected: none. |
| R3 | **Over-reach into Sprint-1.** Temptation to add persistence, topics, or full entities. | Scope fence in §6; G4 rejects any persistence/Kafka/SPI-impl in this PR. |
| R4 | **`WorkItem` supertype shape** locked prematurely as a value type then reshaped as a JPA entity in Sprint-1. | C3: define only the discriminator + contract now; the entity form is P1-E2-S2. |

No ADR is expected for the skeleton itself (it follows DomainModel §1–§2). ADRs are triggered only by R1's future resolution or any R2 deviation.

## 9. Tenant-isolation considerations (AP-2)

- `eip-core` introduces **no persistence and no queries**, so there is no RLS/GUC surface in this task — AP-2's enforcement (tenant_id + RLS + `TENANT_A/TENANT_B` probe) attaches when the first tenant-scoped table lands (Sprint-1), not here.
- **But the types must not foreclose tenancy:** `ExternalRef` and `EventEnvelope` both carry `tenantId` (UUID) as first-class fields (per DomainModel §2.2 / §1.6). This is the type-level guarantee that every tenant-owned entity and every event is tenant-scoped by construction — AP-2 "by construction" begins in the kernel.
- **No individual-surveillance surface (AP-8):** the kernel types carry identity/provenance (`ExternalRef`, `entityId`) but define **no** per-person ranking type, no activity-count type. `Member`/`MemberIdentity` PII types are **not** in this skeleton (they arrive with tenancy/identity in later tasks and live only in mapping rows per DomainModel §2.3). Nothing here can produce an individual metric.

## 10. Testing strategy

- **Leaf invariant (AC-1/AC-5):** ArchUnit rule `no classes in com.eip.core depend on com.eip.{tenancy,connectors,ingestion,analytics,ai,reports,app,workers}` + `ApplicationModules.of("com.eip.core").verify()`; AC-5 demonstrates the guard by a throwaway violating import that turns the build red, then reverting.
- **Type conventions (AC-2/AC-3):** unit tests (AssertJ, JUnit 5, given/when/then, no sleeps, pinned `Clock`) — UUIDv7 is time-ordered/monotonic; time values reject non-UTC; `ExternalRef` keeps `externalId` immutable and distinct from `externalKey`; `EventEnvelope` round-trips with exactly the 11 fields.
- **Coverage:** ≥ 85% line / ≥ 75% branch on `eip-core` (the high tier — TestingStrategy §1; the ratchet is wired in `eip.java-conventions`). Value-object/enum code is simple, so the bar is readily met.
- **Committed Modulith Documenter output** regenerated (CodingStandards §8 "generated code never hand-edited").

## 11. Validation commands

- `cd backend && ./gradlew :eip-core:test` — unit + leaf tests
- `cd backend && ./gradlew :eip-core:check` — + Spotless/Checkstyle/ErrorProne/NullAway/JaCoCo verification (the TASK-0002 gates)
- Inspect the committed Modulith Documenter diff
- (CI mirror: the `backend` job's `./gradlew build check` runs all of the above)

## 12. Definition of Ready assessment

Against [Sprint00DefinitionOfReadyCheck](../../sprints/sprint-00/Sprint00DefinitionOfReadyCheck.md) (TASK-0005 = **PASS***, the `*` being exactly this pre-approval):

- Story P0-E2-S1 ✓ · requirement anchors FR-031/FR-034/FR-035, ADR-014/AD-14 ✓ · change class **CC-1** declared ✓
- Acceptance criteria testable (5 concrete ACs) ✓ · write-set single-module (`/backend/eip-core`), disjoint ✓
- Assigned role R-IE (backend) under R-BA + R-CA matches ModuleOwnership ✓
- Context pack session-sized; forbidden docs (EventModel/DatabasePlan) correctly excluded ✓
- Dependency TASK-0001 **DONE/MERGED** ✓ · size M ✓
- **CC-1 special-type item (DoR §2): R-CA approach pre-approval** → **this document.**

With this recorded, TASK-0005 satisfies G0 and may move **READY → CLAIMED**.

## 13. Recommendation

## **APPROVE WITH CONDITIONS**

The approach is sound and faithful to DomainModel §1–§2, BackendPlan §1, and AP-1/AP-2/AP-3/AP-8. Proceed under these conditions (each verified at G4/G8 on the PR):

- **C1** — `EipException` sealed over **kernel leaves only**; defer the cross-module sealing strategy to an ADR when the first cross-module leaf lands (R1). Do not mis-seal or over-build the taxonomy now.
- **C2** — `secrets`/`storage`/`vector` are **package roots only**; SPI interfaces are defined by their owning tasks (secrets SPI with P0-E4-S1; VectorStore in Sprint-3), not here.
- **C3** — `WorkItem` is the **discriminator + contract** only; no JPA entity, no fields beyond identity/tenancy/type. The persistent entity is P1-E2-S2.
- **C4** — leaf invariant enforced by **ArchUnit + Modulith**; AC-5 demonstrated against a TASK-0001 empty module; committed Documenter output.
- **C5** — `EventEnvelope` carries **exactly** the 11 canonical fields; `payload` is a marker/generic slot (concrete sealed payloads Sprint-1); any field-name/semantics deviation from DomainModel §1 is an anchor change → ADR + G4.
- **C6** — no persistence, no Kafka, no SPI impls, no business logic in this PR (§6 fence).

CC-1 gates remain in force on the PR: **G4 (R-CA + R-BA)** and **G8 two approvals (R-CR + R-CA)**.

## Related documents

- [../../sprints/sprint-00/TaskSpecs.md](../../sprints/sprint-00/TaskSpecs.md) (TASK-0005) · [../tasks/TASK-0001.md](../tasks/TASK-0001.md) (the scaffold this builds on)
- [../../docs/architecture/DomainModel.md](../../docs/architecture/DomainModel.md) §1–§2 · [../../docs/engineering/BackendPlan.md](../../docs/engineering/BackendPlan.md) §1
- [../../engineering-operating-system/ArchitecturePrinciples.md](../../engineering-operating-system/ArchitecturePrinciples.md) (AP-1/2/3/8) · [DefinitionOfReady.md](../../engineering-operating-system/DefinitionOfReady.md) §2 · [CodingStandards.md](../../engineering-operating-system/CodingStandards.md) · [QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md) (CC-1, G4)
