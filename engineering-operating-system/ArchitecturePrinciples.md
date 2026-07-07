# Architecture Principles

Who reads this, when: every engineering role, before designing any change; R-CR and the owning architects when judging G4/G8; R-CA when arbitrating. These are the standing principles derived from the architectural drivers D1–D8 and the accepted ADR set in [ArchitectureOverview §1 and §7](../docs/architecture/ArchitectureOverview.md). They are not aspirations: each principle names the gate that catches its violation. A change that cannot satisfy a principle MUST NOT proceed silently — it escalates to the owning architect and, if the principle itself must bend, produces an ADR ([ADRProcess](./ADRProcess.md)). Note on vocabulary: "engineering agents" here are the roles that build EIP, never the product's 18 runtime agents ([AIAgentCatalog](./AIAgentCatalog.md) vs [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md)).

## 0. Principle index

| ID | Principle | Rooted in | Primary enforcement |
|---|---|---|---|
| AP-1 | Module boundaries are law | D6, D8; ADR-001 | G1 (Modulith/ArchUnit), G4 |
| AP-2 | Tenancy always | D4; ADR-004 | G3, CC-2 → R-SA |
| AP-3 | Events are contracts | D5; ADR-002, ADR-010 | G2 (contract tests), G4 for CC-1 |
| AP-4 | Docs-first | PRD constraint; law 1 of [EngineeringOperatingSystem §5](./EngineeringOperatingSystem.md) | G7 |
| AP-5 | On-prem and air-gap fitness | D1, D2; NFR-050/NFR-051 | G1/G3 (dependency checks), RG1 |
| AP-6 | Fail open on degradation, fail closed on security | D5; SecurityModel | G3, G6, RG2 |
| AP-7 | Read models are derived and rebuildable | D3; ADR-011 | G2 (replay tests), G4 for CC-4 |
| AP-8 | No individual metrics — architectural invariant | D7; FR-057, NFR-071 | G2 (guard tests), G8, RG2 |
| AP-9 | Extend via SPI, never fork the core | NFR-060; ADR-005/006/009 | G4 |
| AP-10 | Observability is part of the change | D5, D6; ADR-013 | G6 |

## AP-1 — Module boundaries are law

**Statement.** The nine backend modules and their allowed-dependency matrix in [ArchitectureOverview §5](../docs/architecture/ArchitectureOverview.md) are binding. A module MUST communicate with another module only through the target's exported `api` interface or through Kafka topics per [EventModel](../docs/engineering/EventModel.md) — never through its tables, repositories, or internal packages.

**Rationale.** Drivers D6 (operability by 1–2 person platform teams) and D8 (evolvability toward services); ADR-001. The extraction path in ArchitectureOverview §2.2 only exists if boundaries never rot.

**Rules.**
- New compile-time dependency edges or topic consumptions outside §5's allowed set are CC-1: G4 with R-CA plus the owning architect, and an ArchitectureOverview §5 update in the same PR.
- `eip-analytics` never calls `eip-connectors`/`eip-ingestion` (events only); `eip-ai` reads analytics only via `MetricQueryService`; cyclic dependencies are build failures (§5 rules 4–6).
- Composition roots (`eip-app`, `eip-workers`) are the only modules allowed to depend on everything, and they contain no business logic ([BackendPlan §1](../docs/engineering/BackendPlan.md)).

**Enforcement.** G1 — `ModularityTests` (`ApplicationModules.verify()`) and ArchUnit topic-consumption rules fail the build ([TestingStrategy §4](../docs/testing/TestingStrategy.md)). G4 for any boundary edit. R-PA and R-CA co-own the boundary map ([ModuleOwnership](./ModuleOwnership.md)).

## AP-2 — Tenancy always

**Statement.** Every tenant-owned row, query, cache key, object prefix, vector collection, and Kafka message key MUST carry tenant scope. Application code always filters by tenant; Postgres RLS is the backstop, not the primary mechanism ([DatabasePlan §5](../docs/engineering/DatabasePlan.md): "RLS is the backstop that makes a missed predicate a non-event instead of a breach").

**Rationale.** Driver D4; ADR-004. Cross-tenant leakage is the worst-case product failure (SecurityModel asset A2).

**Rules.**
- New tenant-scoped table → `tenant_id uuid NOT NULL` + RLS policy pair + RLS test, no exceptions beyond the enumerated platform-scoped tables (DatabasePlan §2).
- The GUC is `app.tenant_id`, transaction-scoped `SET LOCAL`, set by the tenancy filter (API) or from the event envelope `tenantId` (workers) — [SecurityModel §4](../docs/architecture/SecurityModel.md).
- Kafka keys follow [EventModel §7](../docs/engineering/EventModel.md) per topic family (`tenantId:entityId`, `tenantId:jobId`, …); Redis keys use the `eip:{tenantId}:...` namespace; MinIO uses tenant prefixes; RAG retrieval applies the tenant filter inside the VectorStore SPI, not in the caller (SecurityModel §4.3).
- Consumers MUST validate the envelope `tenantId` against target rows before writing.

**Enforcement.** G3 — RLS regression suite, cross-tenant probes, isolation tests run on every PR; anything touching tenancy is CC-2 with R-SA sign-off (A4). The `TENANT_A`/`TENANT_B` probe is mandatory in every repository test class (G2).

## AP-3 — Events are contracts

**Statement.** Every message on an `eip.*` topic MUST use the canonical envelope of [EventModel §2](../docs/engineering/EventModel.md) exactly (`eventId, tenantId, source, entityType, entityId, eventType, occurredAt, ingestedAt, schemaVersion, payload, traceparent`), be published through the transactional outbox, and be consumed idempotently. Delivery is at-least-once by design (ADR-010); duplicate delivery MUST be a non-event.

**Rationale.** Driver D5 (decoupled failure domains); ADR-002, ADR-010. The event log is a derived integration layer — PostgreSQL is the source of record (EventModel §1).

**Rules.**
- Envelope fields, topic names, partition keys, retention, and DLQ naming (`<group>.dlq`, groups `eip.<module>.<purpose>`) come from EventModel §3/§7/§8 and are never restated with drift; changes to any of them are CC-1.
- Payload schema evolution follows EventModel §5: MAJOR.MINOR, additive-only within a major; a field removal/retype without a `schemaVersion` bump fails CI.
- Consumers dedup on `eventId` (or upsert on `ExternalRef` natural keys), commit offsets after processing, park poison messages in their group DLQ with cause headers, and never assume cross-entity ordering.
- No fire-and-forget publishing: producers inside transactions write `event_outbox` in the same transaction ([BackendPlan §6](../docs/engineering/BackendPlan.md)).

**Enforcement.** G2 — event schema contract tests, envelope round-trip tests, idempotent-consumption and DLQ tests (TestingStrategy §5.1, §3). G4 for CC-1 envelope/topic changes. G6 requires lag/DLQ metrics for every new consumer.

## AP-4 — Docs-first

**Statement.** Code MUST NOT silently diverge from /docs. If an implementation must deviate from a spec document, the doc change (plus an ADR when decision-level) lands first or in the same PR; the contract anchors ([QualityGatePolicy §2](./QualityGatePolicy.md) G4: PRD, DomainModel, EventModel, APIDesign, ConnectorFramework SPI sections, SecurityModel) are cited, never paraphrased.

**Rationale.** The [DocumentationQualityReview](../docs/reviews/DocumentationQualityReview.md) found that every recurring defect class was "a secondary document restating an anchor's fact with drift" (§5, recommendation 3). The repo is the shared memory of dozens of agents; an undocumented deviation is a memory corruption.

**Rules.**
- Every task spec declares docs impact; every PR lists docs updated (templates per [AgentCommunicationProtocol §3](./AgentCommunicationProtocol.md)).
- Renegotiating an NFR (e.g. RTO/RPO, retention, Compose sizing — the exact pressure points named in DocumentationQualityReview §5) updates the PRD via ADR and fans out; it never forks in a downstream doc.
- The update matrix in [DocumentationStandards §3](./DocumentationStandards.md) defines which docs each change type MUST touch.

**Enforcement.** G7 — docs impact declared and applied, docs-lint green, on every PR. R-DE owns the gate (A4 on G7).

## AP-5 — On-prem and air-gap fitness

**Statement.** Every dependency, feature, and build step MUST work self-hosted and air-gapped: no SaaS assumptions, no phone-home, no build-time network beyond the locked mirror, all AI features functional against local Ollama/vLLM.

**Rationale.** Drivers D1, D2; NFR-050 (Compose ≤ 15 min on a 16 GB host), NFR-051 (air-gap). This is the product's reason to exist; it cannot be retrofitted.

**Rules.**
- New runtime dependencies pass the [DependencyManagement](./DependencyManagement.md) policy: owning-architect approval, license allow-list (Apache-2.0/MIT/BSD/EPL; GPL-family prohibited in runtime), mirrorable, no telemetry.
- No test may call a real SaaS API or real LLM (TestingStrategy principle 1); the air-gapped CI profile must stay green except the explicitly network-permitting recording-drift job.
- New infrastructure services (anything beyond the ArchitectureOverview §4 container set) are CC-1 decisions with an ADR — driver D6 caps the deployment-unit count.

**Enforcement.** G1/G3 — dependency and license checks in CI; RG1 at phase exit (air-gap scenario in ArchitectureOverview §11 is release-gating); R-DOA owns packaging conformance.

## AP-6 — Fail open on degradation, fail closed on security

**Statement.** Availability failures degrade gracefully and visibly: a failing connector, saturated LLM, or lagging consumer MUST never take down dashboards or the API (ArchitectureOverview §9). Security failures do the opposite: authentication, authorization, tenant scoping, and secret handling MUST fail closed — deny, block, or error; never "temporarily allow".

**Rationale.** Driver D5 and the failure-mode table in ArchitectureOverview §9; the readiness canonical decision in [DocumentationQualityReview §3](../docs/reviews/DocumentationQualityReview.md): eip-app readiness = PostgreSQL + migrations only; Kafka/Redis are degraded-not-unready.

**Rules.**
- Every outbound call has retry (exponential backoff + jitter), circuit breaker, rate limiter, and explicit timeouts (ArchitectureOverview §12 checklist; BackendPlan §7 decorator order).
- Degradation MUST be explicit to users: staleness indicators, DEGRADED health states, "AI temporarily unavailable" — never silent stale data.
- A new feature's failure modes are documented against ArchitectureOverview §9 (or DataFlow) before merge.
- Never fail open: RLS (an unset `app.tenant_id` GUC errors out — DatabasePlan §5), JWT validation, permission checks, webhook signature verification, agent tool allow-lists.

**Enforcement.** G3 for the fail-closed side (CC-2, R-SA blocking verdict); G6 verifies the degraded modes are observable (metrics, alerts); RG2/RG4 at release.

## AP-7 — Read models are derived and rebuildable

**Statement.** Dashboards MUST read pre-aggregated read models (`ops.metric_value`, `mv_*` views), never the canonical event stream at request time; every read model MUST be rebuildable by replaying `eip.domain.*` topics or recomputing from the canonical model.

**Rationale.** Driver D3 (API read load scales with users, not ingestion); ADR-011 (CQRS-lite).

**Rules.**
- No read model is the only home of a fact — the write side (canonical model) is authoritative; a destroyed read model is an inconvenience, not data loss.
- Read-model refresh staleness is surfaced (`computedAt` on dashboard payloads — DatabasePlan §8), consistent with the platform's honesty-about-uncertainty stance.
- New dashboard queries hitting canonical tables directly at request time are a MAJOR architecture finding; hot-path queries are CC-3 → G5 with EXPLAIN evidence.

**Enforcement.** G2 — replay/rebuild tests; G5 for dashboard-latency budgets (dashboards p50 < 500 ms / p95 < 2 s, NFR-010); G4 (R-DBA) for read-model schema changes (CC-4).

## AP-8 — No individual metrics: an architectural invariant, not a UI choice

**Statement.** The platform MUST NOT provide individual-level productivity rankings, leaderboards, or raw per-person activity counts as metrics or exports (FR-057). No release may add individual-ranking capability (NFR-071 — release-blocking). This constrains schemas, APIs, events, exports, and AI outputs — not just screens.

**Rationale.** Driver D7; PRD FR-057/NFR-071; SecurityModel asset A3 (identity data misuse enables surveillance).

**Rules and enforcement points (each one a place a violation is caught).**

| Layer | Rule | Caught at |
|---|---|---|
| Metric registry | People-adjacent metrics are team-grain only; every definition carries caveats/limitations and gaming risks (ArchitectureOverview §12) | G2 metric-registry tests; G8 |
| API/exports | No endpoint or export surfaces per-individual ranked lists — static guard test (TestingStrategy §12) | G2, G3 |
| Individual-level signals | Gated behind `metric.individual.view`: disabled by default, policy-gated, always audited; pseudonymization option per [SecurityModel §7](../docs/architecture/SecurityModel.md) | G3 (CC-2, R-SA) |
| Frontend | Definition popovers on every metric surface; no screen ranks named individuals ([FrontendPlan §3](../docs/engineering/FrontendPlan.md)) | G8, E2E journey (Arda) |
| AI outputs | Validation-agent policy filters reject individual-ranking language (SecurityModel §8) | CC-5 eval suite |
| Release | Anti-surveillance review is part of the security certification | RG2 (R-SA, A4) |

R-PO guards this anti-goal at scope level; any feature request that erodes it escalates to R-PO and the human repository owner. There is no waiver path below RG2.

## AP-9 — Extend via SPI, never fork the core

**Statement.** New integrations and providers MUST be delivered through the published SPIs — Connector, VectorStore, LLM provider, KMS — not by modifying core modules. SPIs are semver-versioned per NFR-060; a breaking SPI change bumps MAJOR and is CC-1.

**Rationale.** NFR-060; ADR-005 (VectorStore SPI), ADR-006 (LLM via provider SPI), ADR-009 (JSON-Schema-driven connector config); [ConnectorFramework §2.1](../docs/engineering/ConnectorFramework.md) defines the compatibility contract (connector declares `spiVersion`; incompatible MAJOR refused at registration).

**Rules.**
- A new connector adds a package and a catalog entry (ConnectorFramework §11, §13) and requires zero frontend code (schema-driven forms) and zero core changes; if it needs a core change, the SPI is deficient — fix the SPI (ADR) rather than special-casing.
- MINOR/PATCH SPI changes are strictly additive (default methods, optional descriptor fields).
- Every connector passes the `ConnectorContractTestKit` K1–K7 (TestingStrategy §5.3) — enforced by an ArchUnit rule.

**Enforcement.** G4 — SPI signature changes are CC-1, reviewed by the owning architect (R-CNA/R-AIA/R-BA per SPI) plus R-CA; G2 runs the contract kit.

## AP-10 — Observability is part of the change

**Statement.** No new endpoint, consumer, or job ships without metrics, traces, and structured logs per [ObservabilityModel](../docs/architecture/ObservabilityModel.md) naming (`eip_*`), dashboard/alert updates for new failure modes, and an SLO impact statement.

**Rationale.** Drivers D5/D6 — a 1–2 person platform team can only operate what it can see; ADR-013 (OTel-first, `traceparent` through the envelope for end-to-end traces).

**Rules.**
- Manual spans on connector SPI operations, agent steps, and report rendering; trace continuity across async hops via the envelope `traceparent` (BackendPlan §12).
- The task spec's observability plan is mandatory or explicitly "none — justified" (task-spec template, [AgentCommunicationProtocol §3.1](./AgentCommunicationProtocol.md)).

**Enforcement.** G6 — R-OE holds the A4 verdict; [ObservabilityRequirements](./ObservabilityRequirements.md) details the per-change checklist.

## 11. Arbitration

Principles occasionally tension each other (e.g. AP-5's minimal footprint vs a scaling need). Resolution order: (1) the owning domain architect proposes; (2) R-CA decides (A1) — recorded as an ADR when decision-level; (3) security disputes end at R-SA's A4 block, overridable only by the human repository owner, recorded. No engineering agent may waive a principle inline in a PR.

## Related documents

- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — gates G0–G8, RG1–RG4, change classes CC-1..7
- [ADRProcess.md](./ADRProcess.md) — how principle-level decisions are recorded and changed
- [CodingStandards.md](./CodingStandards.md) — language-level rules serving these principles
- [QualityGatePolicy.md](./QualityGatePolicy.md) · [ModuleOwnership.md](./ModuleOwnership.md) · [SecurityChecklist.md](./SecurityChecklist.md) · [PerformanceChecklist.md](./PerformanceChecklist.md) · [ObservabilityRequirements.md](./ObservabilityRequirements.md)
- Spec of record: [ArchitectureOverview](../docs/architecture/ArchitectureOverview.md) · [EventModel](../docs/engineering/EventModel.md) · [DatabasePlan](../docs/engineering/DatabasePlan.md) · [SecurityModel](../docs/architecture/SecurityModel.md) · [ConnectorFramework](../docs/engineering/ConnectorFramework.md) · [PRD](../docs/product/PRD.md)
