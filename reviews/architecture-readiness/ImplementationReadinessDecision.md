# Implementation Readiness Decision

> Red-team review board · Date: 2026-07-06 · Decision authority: Chief Architect role, on behalf of the board

## 1. Verdict

## **READY WITH CONDITIONS**

The architecture — the `/docs` specification, the Engineering Operating System, and `/CLAUDE.md` — is ready to support implementation of a multi-year, enterprise-grade, on-premise Engineering Intelligence Platform, **provided the conditions in §3 are honored**. All fourteen review areas returned READY WITH CONDITIONS; none returned NOT READY; every mandatory condition raised by the board was **fixed in the source documents on 2026-07-06** before this verdict was issued.

**The project may proceed to the Implementation Blueprint and Sprint Planning immediately.** No unapplied mandatory fix remains.

## 2. Mandatory fixes before implementation — status: ALL APPLIED

The board raised 49 mandatory findings; all 49 were applied to the source documents in the 2026-07-06 fix wave (47 APPLIED as specified, 2 ADAPTED with recorded justification: DAR-01, MTR-01). By theme:

| Theme | Fixes | Outcome |
|---|---|---|
| Contradiction resolution (two documents, two truths) | SBR-02/03/04, DAR-02, EDA-01/05, MTR-01/02, RAG-01/02, SCL-01/03, FMA-01, OBS-01, DEP-01 | One truth per contract, recorded as ADR-015–019 where decision-level |
| Missing structural decisions | SBR-01/05, DMR-01/02/03/04, DAR-01, EDA-03/04, AIR-01/02, CON-01/02/03/04 | Table/topic ownership catalog, derived-field write path, identity model, failure taxonomy, job-claim semantics, connector archetypes/dispatch/deletion-detection specified |
| Security posture edges | SEC-01/02/03, DAR-03, MCP-01/02/03, RAG-03, AIR-04 | Key-compromise recovery, audit hash-chain DDL, pseudonymous-audit + crypto-shred erasure, MCP delegation/audit, result-side recheck, model capability declaration |
| Observability & failure modes | FMA-02/03, OBS-02/03 | Infrastructure alert set, bounded outage buffers, corrected trace sampling, outbox lag signal |
| Deployment & release safety | DEP-02/03, AIR-03 | Non-GitOps migration gate, backup-target monitoring, eval gates moved off the merge path (ADR-020) |

Full per-fix detail and status columns: each area review's §8 table; decisions: [ArchitectureDecisionUpdates.md](ArchitectureDecisionUpdates.md).

## 3. Conditions of this verdict

1. **Recommended fixes land before Sprint 3.** 112 RECOMMENDED findings are tracked (status SCHEDULED (pre-Sprint-3)) in the review §8 tables. R-TPM converts them into TASK entries during Sprint 1–2 planning; none blocks Phase-0/1 start.
2. **PRD OQ#8 (reference hardware) is resolved during Phase-1 design.** It anchors NFR-003/010/013 benchmarks, the AI minimum-model matrix, and Phase-5 certification. Code may start without it; no benchmark may be published with it open.
3. **Accepted risks stay inside their documented ceilings** (§5). Each ceiling's revisit trigger is normative; crossing a trigger without action is a G4/RG failure.
4. **Phase-0 backfill task** creates `/docs/adr/ADR-001..020` files and the CODEOWNERS/docs-lint automation the EOS mandates.
5. **The inviolable principles (§7) bind every implementation task.** They are enforceable through the EOS gates; violations are BLOCKER-class review findings regardless of code quality.

## 4. Recommended fixes before Sprint 3 (112, tracked)

Distribution: Deployment 13 · AI runtime 9 · Connectors 9 · Data architecture 9 · Scalability 9 · Events 8 · RAG 8 · Security 8 · Domain model 7 · Multi-tenancy 7 · Observability 7 · Failure modes 7 · Service boundaries 6 · MCP 5. Representative items: PgBouncer/CNPG pooler placement in k8s-prod; Redis HA ownership and TLS port truth; per-tenant quota enforcement points in the Kafka lane; OIDC revocation-propagation bound; Confluence ACL flow into RAG filters; MCP capability MAJOR addressing on the wire; connector metric-label cardinality decision; two-datacenter DR topology commitment. Each is a named row with target documents in its review's §8 table — no re-derivation needed at scheduling time.

## 5. Risks accepted for MVP (21, with revisit triggers)

All accepted risks are now documented **in the source documents** with numeric ceilings and triggers. The load-bearing ones:

| Risk | Ceiling / trigger |
|---|---|
| Single PostgreSQL writer (SBR-12, DAR-11) | Committed envelope + ~3–5×; triggers: sustained write TPS, WAL MB/s, autovacuum lag thresholds → levers per the scale-out seam (replicas → partition-per-tenant → extraction) |
| work_item single table (DMR-11) | ~100M rows / ~200 GB; trigger >~150M rows or board-query p95 regression → partition plan |
| pgvector HNSW on shared primary (SCL-14) | ~10M chunks per deployment; trigger → Qdrant per ADR-005/016 |
| Fixed topic partition counts 12/6/3 (SBR-13, SCL-15) | Drain-and-cutover repartition documented; parallelism cap accepted |
| 10M-issue Jira initial sync (EDA-15) | Source-rate-limit bound (multi-day at Jira DC defaults); onboarding expectation documented — not an SLO breach |
| K8s connector poll ceiling (SCL-13) | ~300 namespaces/instance at 20 QPS; shard instances beyond |
| Per-tenant API rate limits & storage quotas pre-Phase-3 (MTR-10) | Interim: HPA bounds + request limits; FR-144/FR-130 land Phase 3 |
| Cooperative-only agent cancellation (AIR-15) | Worst-case cancel latency = longest single LLM call |
| MCP semantic manipulation by registered servers (MCP-09) | Schema/size/screening pass ≠ semantic trust; mitigations documented |
| RAG ACL staleness window (RAG-12) & retrieval poisoning (RAG-13) | ≤15-min sync guidance; adversarial-ranking residual named |
| Air-gap file-provider KMS at-rest strength (SEC-10) | Equals mounted-secret/volume encryption; Vault where available |
| Redis failover rate-limit reset burst (FMA-09); model-quality regressions caught nightly not per-merge (AIR-14); silent-truncation reconciliation deferred to FR-041 (CON-13); blob filter defaults (CON-14); restore-then-backfill outside RTO clock at 10× (DEP-18); envelope-sized topologies (DEP-17); low-traffic SLO burn flap (OBS-10) | As documented per finding |

## 6. Open questions

**Resolved at decision level by the fix wave** (residual detail belongs to the named phase design task): topic/group ownership and the eip-app no-Kafka rule (AD-2 — G4 co-ownership arbitration defaults to R-CA); analytics canonical access (AD-1/ADR-019 — JPA mapping placement and Flyway-split-at-extraction are Phase-1 design notes); outbox relay topology and buffer bounds (AD-3/ADR-017 + FMA-03); consumer idempotency mechanics (EDA-04 — DLQ park-index detail in Phase-1 design); audit hash-chain DDL (SEC-02 — sync-vs-async chaining finalized in Phase-0 audit task P0-E3-S4); sync dispatch (CON-02); deletion detection (CON-04); materialized views (ADR-015 — question dissolved).

**Open, non-blocking (13), assigned:**

| # | Question (abridged) | Area | Owner / when |
|---|---|---|---|
| 1 | Reference hardware anchoring all capacity/SLO commitments (PRD OQ#8) | scale | R-CA + R-PE, Phase-1 design (condition #2) |
| 2 | Per-tenant ingest/storage quota enforcement points in the Kafka lane; erasure reach into archives/backups | tenancy | R-PA + R-SA, Phase-2 design |
| 3 | Quantified noisy-neighbor freshness contract; whale-tenant threshold; >50-tenant sharding path | scale | R-PE, Phase-2 design |
| 4 | OIDC revocation-propagation bound (denylist vs ≤15-min token expiry) | security | R-SA, Phase-0 design note |
| 5 | pgvector min version & filtered-HNSW semantics; 10M vs 20M ceiling reconciliation; Qdrant migration decision forum | rag | R-AIA + R-DBA, Phase-3 design |
| 6 | Confluence ACL flow into retrieval filters; grant-cache invalidation; shared injection-screening engine; adversarial eval corpus | rag | R-AIA + R-SA, Phase-3 design |
| 7 | MCP capability MAJOR addressing on the wire; resource-read rate limits | mcp | R-AIA, Phase-4 design |
| 8 | Correlation-edge materialization at 10⁸ scale; Member merge re-attribution; rollup storage; business-hours calendars; drill-down after retention | data | R-DA, Phase-2 design |
| 9 | Multi-instance sharding of one source; customer K8s RBAC manifest; push/stream connectors on the batch SPI; third-party connector packaging air-gapped; cursor compatibility | connectors | R-CNA, Phase-1–2 design |
| 10 | PgBouncer/pooler in k8s-prod; Redis HA/TLS truth; two-DC DR pattern; NetworkPolicy generation mechanism; non-GitOps ordering (partially closed by DEP-02) | deploy | R-DOA, pre-Sprint-3 (RECOMMENDED set) |
| 11 | Connector metric label cardinality; long-term metrics store; air-gap dead-man receiver; log access without Loki; backfill alert windowing | observability | R-OE, pre-Sprint-3 (RECOMMENDED set) |
| 12 | Redisson run-lock lease semantics for 60-min runs; crashed-attempt budget accounting; Python-worker parity; report-storm admission smoothing; per-model prompt variants; parse-repair eval realism | ai | R-AIA, Phase-3 design |
| 13 | G4 arbitration mechanics on co-owned modules (default: R-CA per escalation chain — formalize in ModuleOwnership) | boundaries | R-CA, Sprint 1 EOS patch |

## 7. Architecture principles that must not be violated during implementation

Distilled by the board from all ten reviews (full statements below are normative; citations point at the enforcing documents):

1. **AI is additive and never trusted.** Every non-generative capability works with zero LLM providers configured; generative failure never blocks ingestion, analytics, dashboards, or RBAC; every factual claim in agent output traces to a tool result; validation is never bypassable; cancelled/exhausted runs release slots and locks and publish nothing partial; degradation is explicit — never fabricated content or citations. (AgentArchitecture §1–§2, §6, §13; FR-087)
2. **Every agent, retrieval, and MCP call runs as the initiating principal.** RBAC ∩ static capability manifest; tenantId injected by the runtime, never model- or caller-suppliable; budgets are pre-call hard stops that fail loud; retrieved chunks, metadata, and tool results are data, never instructions — they can never widen allow-lists, budgets, or control flow, and changed external tools suspend until re-approval. (SecurityModel §8; AgentArchitecture §3–§4; RAGArchitecture §3.3)
3. **Module boundaries are structural.** One owner and one writer per table and per topic; cross-module access only via exported APIs, events, or the enumerated read-only canonical grant; no cross-schema FKs; composition roots and eip-core own no business logic or tables; app→workers is events-only; every new edge updates the docs matrix and the ArchUnit matrix in the same PR — they are one artifact. (ArchitectureOverview §2.2/§5/§10; DatabasePlan §1–§2)
4. **Outbox out, idempotent in.** No producer writes Kafka outside the outbox (raw intake's direct-produce carve-out per ADR-017 exactly as scoped); consumers dedup and ack only after the idempotent write commits; ledger retention > topic retention + replay window; per-key ordering is never salted away; consumer-group membership never depends on run duration; unknown MAJOR → DLQ with committed offsets; delivery semantics tested only against a real broker. (EventModel; FR-038; ADR-017)
5. **PostgreSQL is the sole system of record.** Kafka is transport, never primary-state reconstruction; every derived store is rebuildable and declares owner + staleness contract; internal identity is EIP-minted UUIDv7 — source identity lives only in ExternalRef; append-only tables are corrected by new rows; metric history is immutable per definition version; occurredAt is never overwritten. (ADR-003/011; DomainModel §2/§14; FR-062)
6. **Tenant isolation is structural and ≥2 layers deep on every path.** tenant_id + forced RLS via transaction-scoped SET LOCAL only; consumers bind tenancy from the envelope before any table access; SPI layers inject isolation filters so forgetting a filter cannot leak; caches key on tenantId (+principalGrantHash where permission-relevant); every unique constraint and hot index includes tenant_id (the sharding seam); isolation-test failures have no waiver; suspected isolation failure is S1 fail-closed. (FR-128/129; NFR-041; ADR-016/018)
7. **Deny by default; fail closed on security, open on degradation.** No endpoint or MCP capability without a declared permission; push-down filtering plus result-side recheck; MCP is a thin adapter over the same RBAC/RLS — never a second, weaker API; authn/authz/RLS/signatures/audit-coupled mutations never "temporarily allow"; readiness gates on PostgreSQL+Flyway only — a failing connector, saturated LLM, lost cache, or lagging consumer never takes down the API. (SecurityModel §4/§9; ObservabilityModel §9; NFR-020)
8. **No individual surveillance or ranking, ever, through any change.** No stream collects individual-activity data beyond team-mapping metadata; no agent, prompt, or routing change may introduce individual scoring; attribution flows only through MemberIdentity. This is architecture, not configuration, and it is release-blocking. (FR-057; NFR-071)
9. **Air-gap purity and zero unauthorized egress.** No telemetry, phone-home, license checks, or runtime downloads — including bundled components' defaults; egress = tenant-configured connectors + allow-listed MCP servers + the optional LLM endpoint, with NetworkPolicies generated from registered configuration; every release ships as a signed offline bundle smoke-installed on a no-network host; TLS validation is never disableable. (NFR-051; DeploymentModel §10/§12)
10. **Secrets and PII are protected at write time, everywhere.** Envelope encryption via the KMS SPI is the only secret storage path; credentials appear only as per-run in-memory references; no un-pseudonymized PII enters append-only or immutable stores — erasability (FR-142) is designed in at write time, never retrofitted; telemetry never carries secrets, PII, or prompt bodies. (SecurityModel §6/§11)
11. **Audit completely or refuse to act.** An unaudited mutation cannot commit; the trail is append-only, tamper-evident, 100% of NFR-042 categories, with no direct personal identifiers in chained rows; every LLM call — including cache hits, fallbacks, and cancelled calls — flows through the single audited funnel; auditable flows are never sampled away. (NFR-042; SecurityModel §11)
12. **Connectors act only through SyncContext.** No direct Kafka/Postgres/Redis/MinIO access; webhooks are a latency optimization over the same pipeline, with polling reconciliation the source of completeness including deletions; checkpoints commit only after durable staging; failure isolation is per instance; configured source rate ceilings are never breached in aggregate; simulation exercises the real pipeline; the SPI is a semver'd published contract. (ConnectorFramework; FR-018)
13. **The hot path is structurally bounded.** Dashboards never read canonical tables at request time — precomputed series and RLS-protected read models only (ADR-015); keyset pagination only; read and write load never share a queue; high-volume tables arrive with a partitioning plan; frontend budgets (shell ≤250 KB gzip, chunks ≤200 KB, virtualization >200 rows) are CI-enforced. (ADR-011/015; DatabasePlan §8)
14. **Every change ships its evidence and instrumentation; gates stay deterministic.** Metric + trace + log + alert + runbook anchor land with the change (unobservable = unfinished); labels are bounded; hot-path changes carry EXPLAIN evidence at representative volume; scale numbers live in one place and are quoted, never re-derived; no merge-gating test uses a live LLM (ADR-020); embedding/chunking/reranker changes pass golden-set regression before cutover. (ObservabilityModel §3/§7; TestingStrategy §1)
15. **Reversibility is drilled, not asserted.** Expand–contract, N-1-compatible schema and event changes; migrations complete before new code serves; one-version image rollback never needs a DB restore; a backup without a tested restore — or inside the primary failure domain — does not exist; RTO/RPO are release-gated drill results. (NFR-021/022; DeploymentModel; RG4)

## 8. Decision

Proceed to **Implementation Blueprint and Sprint Planning**. Phase 0 begins with: the ADR backfill task (ADR-001..020), CODEOWNERS + docs-lint automation, the audit-subsystem design note (hash-chain finalization), and R-TPM's conversion of the 112 RECOMMENDED findings into scheduled tasks. The board re-convenes at each phase boundary per RG1–RG4.

## Related documents

- [ArchitectureReadinessReview.md](ArchitectureReadinessReview.md) — board synthesis
- [ArchitectureDecisionUpdates.md](ArchitectureDecisionUpdates.md) — ADR-015–020 + clarifying decisions
- Area reviews (this directory) — findings, extreme-scenario tables, fix status
