# Documentation Quality Review — Engineering Intelligence Platform (EIP)

**Date:** 2026-07-06
**Scope:** Full specification workspace — `README.md` + all 30 documents under `/docs`
**Method:** Six parallel deep reviews (product, architecture, engineering, AI, infrastructure/operations/testing/implementation, plus a dedicated cross-document consistency audit over 20 fact classes), followed by scripted mechanical checks (link resolution, requirement-ID integrity, placeholder scan), a canonical-decision arbitration of every conflict, a coordinated fix pass across all affected documents, and post-fix verification sweeps (numbers, naming conventions, product-catalog arithmetic, link/ID re-check).

---

## 1. Summary of Reviewed Documents

| Cluster | Documents | Verdict before fixes |
|---|---|---|
| Vision & Product | [Vision](../vision/Vision.md), [PRD](../product/PRD.md), [Personas](../product/Personas.md), [UserJourneys](../product/UserJourneys.md), [FeatureCatalog](../product/FeatureCatalog.md), [UseCases](../product/UseCases.md), [AcceptanceCriteria](../product/AcceptanceCriteria.md), [Roadmap](../product/Roadmap.md) | Strong and arithmetic-clean, but FeatureCatalog/Roadmap contradicted the PRD on three phase placements and on priority semantics; the AC coverage claim was inaccurate; two enterprise product gaps (DSAR/erasure, accessibility). |
| Architecture | [ArchitectureOverview](../architecture/ArchitectureOverview.md), [DomainModel](../architecture/DomainModel.md), [ComponentModel](../architecture/ComponentModel.md), [DataFlow](../architecture/DataFlow.md), [DeploymentModel](../architecture/DeploymentModel.md), [SecurityModel](../architecture/SecurityModel.md), [ObservabilityModel](../architecture/ObservabilityModel.md) | Deep, load-bearing content (DomainModel the strongest file in the workspace), but numeric contradictions with the PRD's NFRs (capacity 36×, freshness 15×, sizing, RPO/RTO), a real dedup-window defect, and cross-file drift (RLS GUC name, DLQ scheme, retention). |
| Engineering | [BackendPlan](../engineering/BackendPlan.md), [FrontendPlan](../engineering/FrontendPlan.md), [DatabasePlan](../engineering/DatabasePlan.md), [ConnectorFramework](../engineering/ConnectorFramework.md), [EventModel](../engineering/EventModel.md), [APIDesign](../engineering/APIDesign.md) | Implementation-grade, but the contract seams disagreed: webhook paths, DLQ naming, RLS GUC, connector status enum vs lifecycle, checkpoint record vs table, missing operational tables in the DB plan, no SPI semver despite NFR-060. |
| AI | [AgentArchitecture](../ai/AgentArchitecture.md), [RAGArchitecture](../ai/RAGArchitecture.md), [MCPArchitecture](../ai/MCPArchitecture.md) | Detailed and roster-exact (all 18 agents match FR-082), but RAG lacked prompt-injection defenses for retrieved content, the runtime lacked cancellation (FR-086), no AI metrics catalog, no concurrent-run quota (FR-130), no FR/NFR traceability. |
| Infra / Testing / Ops / Implementation | [LocalDevelopment](../infrastructure/LocalDevelopment.md), [DockerCompose](../infrastructure/DockerCompose.md), [KubernetesOpenShift](../infrastructure/KubernetesOpenShift.md), [TestingStrategy](../testing/TestingStrategy.md), [OperationsGuide](../operations/OperationsGuide.md), [PhaseBasedImplementationPlan](../implementation/PhaseBasedImplementationPlan.md) | Broad and operator-focused, but OperationsGuide carried the most consequential errors (RTO/RPO contradictions, wrong actuator port, wrong bucket names); enterprise proxy/CA and certificate management were missing; several command/target/package-manager inconsistencies. |

The cross-document audit found 17 of 20 fact classes fully consistent before fixes (product naming, 18-agent roster, event envelope, stack versions, module names, ports, phase assignments for MCP/K8s/GitLab, frontend stack, identity, encryption, vector store, LLM providers, table naming, API conventions, relative links, personas, metric names) — the workspace was already unusually coherent; the defects were concentrated, not diffuse.

## 2. Issues Found

Approximately **135 distinct findings** (after deduplicating overlaps between reviewers): **22 high**, **66 medium**, **47 low** severity. Zero broken links and zero placeholder content existed before or after the fix pass.

### 2.1 High-severity (all fixed)

**Contradictions with the PRD (source of record):**
1. Delivery-risk features (FEAT-032/094/097/113/116) scheduled Phase 2 in FeatureCatalog/Roadmap vs Phase 3 in PRD FR-053/FR-066, Vision, Personas.
2. Validation Agent (FEAT-148) scheduled Phase 4/P1 vs PRD FR-087 P0/Phase 3 — it gates output publication from Phase 3.
3. FEAT-034 (notification channels, Phase 4) was a hard dependency of Phase-2 FEAT-210 and of Phase-1/2 exit ACs — a dependency-order violation.
4. ArchitectureOverview driver D3 and its §11 scale scenario used "1,000 domain events/second" — ~36× NFR-003 (100k events/hour); the PRD Phase-1 exit tests 100k/h.
5. ObservabilityModel's ingestion-freshness SLO (≤ 15 min webhook) was 15× looser than NFR-012 (60 s p95); its burn alert (> 900 s) could never detect an NFR-012 breach; DataFlow claimed a third number (≤ 5 s).
6. DeploymentModel's Compose baseline (8 vCPU / 32 GB) contradicted NFR-050 (16 GB host); its §13 acceptance tested the wrong host size.
7. RTO figures: OperationsGuide "≤ 4 h K8s / ≤ 8 h pilot" and DeploymentModel "≤ 1 h enterprise" vs NFR-022 (≤ 30 min HA / ≤ 4 h single-node); the 30-minute HA target appeared in no document outside the PRD.
8. RPO: nightly-only backups (24 h) on the single-node path vs NFR-021 (≤ 15 min); object-storage RPO ≤ 24 h.
9. DatabasePlan raw-staging retention 30 days vs NFR-070 default 90 days.
10. Phase-5 exit criteria omitted the 99.9 % availability (NFR-020) and ≤ 30 min HA RTO (NFR-022) targets its own phase exists to deliver.

**Cross-document contract drift:**
11. RLS session variable: `eip.tenant_id` (SecurityModel, BackendPlan) vs `app.tenant_id` (DatabasePlan, ArchitectureOverview, ComponentModel, DataFlow) — a load-bearing name RLS policies reference literally; plus session-scoped "per-connection" wording that is unsafe under PgBouncer transaction pooling.
12. DLQ naming: `<topic>.<group>.dlq` with coarse groups (architecture docs, BackendPlan) vs EventModel's `<group>.dlq` with fine-grained groups (`eip.<module>.<purpose>`) — different topic names and cardinality.
13. Webhook endpoint: `/api/v1/webhooks/...` (ConnectorFramework, DataFlow) vs APIDesign's `/webhooks/v1/{connectorId}/{source}` (explicitly outside `/api/v1`).
14. Connector status enum in DatabasePlan (`ACTIVE/PAUSED/ERROR/DRAFT`) could not represent ConnectorFramework's lifecycle state machine (REGISTERED→…→DISABLED).
15. `processed_events` dedup-ledger retention (14 days) was shorter than domain-topic retention (30 days), violating its own stated invariant — a real double-processing risk given Redis is fail-open.
16. Retention values for the same data classes differed across DataFlow, SecurityModel, DeploymentModel, and NFR-070 (raw 30 vs 90 days; metrics 25 vs 37 months vs indefinite; audit 24 vs 25 months).
17. ADR reference "ADR-0001 … ADR-0005 (secrets design)" in the implementation plan: wrong digit format and wrong content (ADR-005 is pgvector; secrets is ADR-014).
18. Readiness probes gated on Kafka/Redis connectivity, contradicting the platform's own fail-open degradation model — a broker blip would have caused a full API outage by design.
19. Broken section references: BackendPlan → "APIDesign §12" (doesn't exist); DatabasePlan → "DomainModel §13.6" (doesn't exist); DockerCompose → "Section 14" (doc ends at 13).
20. AI runtime had no cancellation path or `cancelled` state despite FR-086 mandating cancellation semantics.
21. RAG treated retrieved enterprise content (Jira comments, wiki pages — attacker-writable) as trusted; no prompt-injection defenses, even though the MCP document explicitly labels the equivalent content untrusted.
22. OperationsGuide pointed operators at the wrong actuator port (8080 vs 8081) and at `localhost` for a container that is not host-published — every §14 diagnostic command would have failed.

### 2.2 Medium-severity themes (all fixed)

- **Missing acceptance criteria:** the §13 full-coverage claim was false — P0 FEAT-002 and the four P1 dashboards (FEAT-115–118) among 15 features had no AC.
- **Missing enterprise/product requirements:** no data-subject erasure/DSAR capability despite GDPR/works-council buyers (PRD OQ#7); no accessibility requirement despite public-sector targeting; no inbound per-tenant API rate limiting.
- **Missing async/event details:** outbox flow omitted the raw/webhook producer path; no unknown-major-version→DLQ rule (FR-038); partition-key example keyed by tenant only (hot partition + broken per-entity ordering).
- **Missing connector-framework details:** no SPI semver/compatibility policy (NFR-060/FR-018); checkpoint record fields didn't map to the checkpoint table; error taxonomy mismatched the sealed exception set; webhook-ack SLA stated as 2 s in one doc and 500 ms in another.
- **Missing AI details:** no AI metrics catalog; no per-tenant concurrent-run quota (FR-130); no phase column on the agent catalog (FR-083); no context-window management; undefined "cache-hit" dashboards; unnamed air-gap embedding default; `retrievalAuditId` required by the citation contract but produced nowhere; no MCP capability versioning; no outbound-argument DLP (exfiltration channel); unsanitized generated HTML (stored-XSS vector).
- **Missing observability:** no SLO/alert for NFR-011 (API p95 < 300 ms); freshness measured at the wrong boundary; error-budget arithmetic off.
- **Missing deployment/ops content:** no egress-proxy or custom-CA support anywhere; no certificate issuance/rotation for K8s; no DLQ-growth alert; no RLS-misconfiguration runbook; tenant lifecycle missing suspend/delete/export-erasure.
- **Missing testing:** NFR-003 burst (3× for 15 min) untested; NFR-013 unexercised; `npm` vs pinned `pnpm`; undefined `make dev` target as the NFR-050 gate.
- **Terminology drift:** role model described three different ways (Personas vs FeatureCatalog vs UC-001); SecurityModel's RBAC matrix used a fourth role set (`ORG_ADMIN`/`MANAGER`/`AUDITOR`); simulation-pack catalogs and paths diverged across three docs; demo realm roles (`ORG_ADMIN`/`ENGINEER`) matched nothing; report job states vs artifact states unmapped; `traceparent` vs `traceId` in audit records.

### 2.3 Low-severity (all fixed)

Metric-name spelling drift (`lag.seconds` vs `lag_seconds`), placeholder-host inconsistency (`docs.eip.local` vs `eip.example.com`), locale `en` vs `en-US`, `redis:7` vs `redis:7-alpine`, self-directory `../architecture/` links, imprecise "§1.6" citation, E2E smoke subset stated two ways (E1–E3 vs E1, E3, E7), Grafana/Keycloak port listings in the ops tables, `KC_HTTP_RELATIVE_PATH` assumption undocumented, error-budget arithmetic, driver table citing prose instead of NFR IDs, sizing narratives inconsistent with the stated scale envelope, one literal broken cross-reference ("see J-…"), FIPS/SELinux unaddressed, vague LLM-exception HTTP mapping, and similar.

## 3. Fixes Applied

**29 of 31 files changed (+658/−339 lines); ~190 distinct fix operations.** `Vision.md` and `README.md` needed no changes — both sat on the correct side of every conflict. All fixes were governed by a single canonical-decision record so that every document landed on the same value. The key decisions:

| Decision | Canonical value now used everywhere |
|---|---|
| RLS GUC | `app.tenant_id`, transaction-scoped `SET LOCAL`, PgBouncer transaction pooling required |
| DLQ naming | `<group>.dlq`, one per fine-grained consumer group `eip.<module>.<purpose>` (per EventModel §8's real group names) |
| Partition keys | Literal colon notation per topic family (`tenantId:entityId`, `tenantId:jobId`, …); PRD keeps conceptual phrasing |
| Capacity | NFR-003 figures (100k events/h + 3× burst 15 min); "1,000 ev/s" reframed as clearly-labeled internal fan-out headroom |
| Freshness | NFR-012 (60 s p95 webhook / interval + 5 min poll); SLO, burn alert, and measurement boundary aligned; intake ack ≤ 500 ms |
| RTO/RPO | ≤ 30 min HA failover / ≤ 4 h single-node restore; RPO ≤ 15 min production (WAL + continuous object replication), 24 h explicitly demo/eval-only |
| Retention | Single table in SecurityModel §7 (raw 90 d, canonical/metrics indefinite-by-default per NFR-070, AI logs 13 mo, audit 25 mo, dedup ledger 35 d > topic retention); other docs reference it |
| Compose sizing | 16 GB baseline (no local LLM) meets NFR-050; Ollama is an optional +8 GB profile |
| Phases/priorities | PRD per-phase P0 semantics; FEAT-032/094/097/113/116/148 → Phase 3; FEAT-034 → Phase 2; FEAT-093/136/137/138/148 + MCP features → P0; all counts recomputed from the actual tables |
| Role model | 4 built-in base roles (PLATFORM_ADMIN, TENANT_ADMIN, ANALYST, VIEWER) + 6 seeded persona templates — now identical in Personas, FeatureCatalog, UseCases, SecurityModel, LocalDevelopment |
| Webhooks | `POST /webhooks/v1/{connectorId}/{source}`, outside `/api/v1`, 202 ≤ 500 ms (APIDesign authoritative) |
| Readiness | eip-app readiness = PostgreSQL + migrations only; Kafka/Redis degraded-not-unready |

**New content added to close gaps (not just corrections):**

- **PRD:** FR-142 (data-subject erasure & DSAR export), FR-143 (WCAG 2.1 AA), FR-144 (per-tenant API rate limiting); scoped FR-128 wording; NFR-021 production/demo scoping; ID-gap policy note.
- **Product:** FEAT-211/212/213 with catalog rows; seven new acceptance criteria (AC-097–AC-103) covering org hierarchy, all four remaining dashboards, erasure, accessibility, and rate limiting; honest §13 traceability list; Roadmap data-export scope statement.
- **AI:** RAG prompt-injection-defense section (retrieved content = untrusted input, provenance-delimited blocks, screening, audited flags); agent cancellation (state + `POST /api/v1/ai/runs/{id}/cancel`); per-tenant concurrent-run quotas; AI metrics catalog; context-window management; LLM response-cache definition with tenant/permission-scoped keys; named air-gap embedding default (bge-small-en-v1.5, 384-dim ONNX); `retrievalAuditId` plumbing; MCP capability versioning + deprecation; outbound-argument DLP; shared-inference tenant isolation; HTML sanitization for generated artifacts; FR/NFR traceability in all three docs.
- **Engineering:** Connector SPI semver & compatibility section; outbox coverage of the raw/webhook path; unknown-major→DLQ rule; missing operational tables (outbox, dedup ledger, worker heartbeat, Quartz) added to the DB plan with RLS/retention notes; GeneratedReport provenance columns (FR-114); checkpoint field↔column mapping; permission-catalog completion (org:read, board:read, release:read, risk:read); PNG/SVG export formats (FR-117).
- **Infra/Ops/Testing:** Enterprise network integration (egress proxy + custom CA) for Compose and K8s; K8s certificate management; FIPS/SELinux notes; WAL-archiving + continuous MinIO mirroring backup options; HA-failover drill; DLQ-growth alert; RLS-misconfiguration runbook; tenant suspend/delete/export-erasure lifecycle; ingest-burst load-test scenario; NFR-013 validation note; Phase-5 exit criteria completed with NFR-020/022 targets.

**Post-fix verification results:** all relative links resolve (0/65+ broken); every referenced ID resolves to a definition (104 FR, 22 NFR, 145 FEAT, 103 AC, 18 UC, 14 ADR; zero dangling); no placeholder content; FeatureCatalog/Roadmap counts match actual table rows exactly; no stale DLQ forms, GUC names, role tokens, ports, bucket names, package-manager or make-target references remain (verified by scripted sweeps).

## 4. Remaining Risks

1. **Open questions still open (by design).** PRD §11 OQ#1 (correlation key-pattern defaults), OQ#2 (metric-series versioning UX), OQ#3 (delay-prediction approach — now correctly phased with the Phase-3 features it gates), OQ#5 (initial MCP capability catalog), OQ#6 (air-gapped PDF/PPTX renderer selection), OQ#8 (reference hardware for NFR benchmarks) remain unresolved. Each is annotated with its target phase; none blocks Phase 0–1, but OQ#8 must be settled before any NFR-013/NFR-003 benchmark is meaningful.
2. **Aggressive HA targets are now consistent but unproven.** RTO ≤ 30 min and RPO ≤ 15 min are commitments the documentation makes coherently; whether the Phase-5 reference topology actually achieves them will only be known in the timed drills the plan now requires. If drills miss, amend NFR-021/022 via the ADR process rather than letting drift return.
3. **Numeric coupling is maintenance-sensitive.** NFR values now appear (correctly cross-referenced) in ~6 documents each. Any future change to an NFR must fan out; consider adding a CI docs-lint (the scripted checks used in this review are reusable) that greps for the canonical values.
4. **Count integrity in FeatureCatalog/Roadmap is manual.** Totals were recomputed programmatically in this pass, but the next feature addition can silently break them again. A CI check that recounts table rows against the stated totals is cheap insurance.
5. **New requirements are un-sized.** FR-142 (erasure/DSAR) in particular has non-trivial technical depth (vector-index deletion, artifact redaction, audit-preserving anonymization); it is scheduled Phase 2/P1 but has no design document yet.
6. **The bundled embedding/reranker defaults were chosen for licensing and air-gap fit, not by evaluation.** Validate retrieval quality against the golden datasets in the TestingStrategy before GA of Phase 3.
7. **Simulation packs are load-bearing but unspecified in detail.** demo-small/demo-midsize/demo-troubled/enterprise-large are now named consistently and drive Day-1, CI, and perf testing — but no document specifies their exact contents/volumes yet (Phase-1 deliverable to define).

## 5. Recommendations Before Implementation

1. **Resolve OQ#8 (reference hardware) first** — it anchors every performance NFR that Phase-1 exit criteria test against.
2. **Add a docs-lint CI job in the Phase-0 scaffolding PR set**: relative-link check, ID-reference resolution, FeatureCatalog/Roadmap count reconciliation, and greps for the canonical values (GUC name, DLQ pattern, NFR figures). All four checks already exist as scripts from this review and cost seconds.
3. **Treat EventModel, APIDesign, DomainModel, and the PRD as the four contract anchors** (topics/envelope, REST surface, vocabulary, requirements). The review's recurring failure mode was a secondary document restating an anchor's fact with drift — restating should be replaced by referencing wherever practical.
4. **Write the FR-142 (erasure/DSAR) design note during Phase 1**, before RAG indexing exists — retrofitting subject-deletion onto a populated vector store is far harder than designing for it.
5. **Run the anti-surveillance review (NFR-071) against the SecurityModel's `metric.individual.view` policy gate early** — the permission exists in the matrix; its governance process should be defined before any Phase-2 dashboard ships.
6. **Keep the ADR process honest**: three of the highest-impact fixes in this review (RTO targets, Compose sizing, retention defaults) are exactly the kind of decisions that will face pressure during implementation. Any renegotiation should update the PRD NFR and fan out, not fork quietly in a downstream document.
7. **Before Phase-3 code, prototype the RAG prompt-injection screen** added to RAGArchitecture — it is the one new security control in this pass with meaningful false-positive/false-negative tuning risk.

---

*Review artifacts: six reviewer reports and the canonical-decision record are session artifacts; the scripted verification checks are reproducible from the descriptions in §3. This file is the durable summary of record.*
