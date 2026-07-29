# RAG Architecture Review — Architecture Readiness Board

> Reviewer: Principal AI Architect (RAG & MCP security) · Date: 2026-07-06 · Verdict: **READY WITH CONDITIONS** — mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items.

## 1. Scope & reviewed documents

Primary subject: [docs/ai/RAGArchitecture.md](../../docs/ai/RAGArchitecture.md) (all sections). Reviewed against:

- [docs/architecture/SecurityModel.md](../../docs/architecture/SecurityModel.md) — §4 (enforcement layers), §5 (multi-tenancy), §8 (AI-specific security), §11 (audit), §13 (testing checklist)
- [docs/product/PRD.md](../../docs/product/PRD.md) — FR-095–FR-101, NFR-001–NFR-004, NFR-041, NFR-042, NFR-051
- [docs/ai/AgentArchitecture.md](../../docs/ai/AgentArchitecture.md) — §4 (ToolRegistry rules), §5.14 (RAG Retrieval Agent), §5.16/§6.2 (Validation Agent), §7 (routing)
- [docs/ai/MCPArchitecture.md](../../docs/ai/MCPArchitecture.md) — §2.4 (symmetric injection defenses)
- [docs/engineering/DatabasePlan.md](../../docs/engineering/DatabasePlan.md) — §11 (vector schema/indexing)
- [docs/architecture/ArchitectureOverview.md](../../docs/architecture/ArchitectureOverview.md) — ADR-005, D3 scale envelope
- Governance: [CLAUDE.md](../../CLAUDE.md), [engineering-operating-system/SecurityChecklist.md](../../engineering-operating-system/SecurityChecklist.md), [engineering-operating-system/TestingChecklist.md](../../engineering-operating-system/TestingChecklist.md), [engineering-operating-system/QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md)

Calibration: committed envelope is PRD §6 (≥10k repos, ≥1M WorkItems, ≥50 tenants, 100k events/h); extreme scenarios below are ~10× that.

## 2. Extreme-scenario assessment

| Scenario | Holds / Degrades / Breaks | Reasoning (doc + section) | Mitigation path |
|---|---|---|---|
| 100,000+ repositories (docs indexing) | **Degrades** | RAGArchitecture §2 defaults to markdown/docs globs, code opt-in — sane volume control. 100k repos' docs plus wikis plausibly exceeds the "low tens of millions of chunks" pgvector sweet spot (§6). Initial full index at 1–3k chunks/min (§15) takes weeks on one GPU. | Qdrant path (ADR-005) is non-foreclosed; per-tenant indexing partitions (§9) scale consumers. Needs RAG-10 (trigger governance) and RAG-08 (window sizing). |
| 10,000,000+ Jira issues | **Degrades** | One logical document per issue (§2) → 10M+ chunks in one deployment, at/over pgvector ceiling (§6: ~20M criterion; ArchitectureOverview ADR-005 says "10M+" — thresholds disagree). Hybrid FTS leg stays in Postgres (§3.2) and grows with the corpus regardless of vector store — FTS scale-out is not addressed. | Qdrant for vectors; Postgres read replicas (§15) for FTS. RAG-10 aligns trigger; FTS ceiling documented as accepted risk (RAG-13). |
| 500,000-page Confluence, per-page ACLs | **Degrades → breaks on revocation SLO** | §8.1 syncs ACLs "on the connector's incremental sync cadence"; §8.4 recommends ≤15 min sync where revocation matters. Confluence emits no reliable restriction-change webhooks; detecting per-page restriction changes across 500k pages within 15 min implies a full restriction sweep the source API rate limits will not sustain. Storage of grants is fine (`rag_acl_grant`, §3.2); the sync *time* and staleness window are not. | RAG-07: specify the restriction-change detection mechanism and its documented ceiling; stale-beyond-threshold drop-from-retrieval option (§16) is the correct fail-closed backstop and should be the stated default for revocation-sensitive spaces at this scale. |
| 10,000+ users querying (high retrieval QPS) | **Degrades** | Retrieval cache keys on `(tenantId, principalGrantHash, queryHash)` (§8.5) — correct for isolation, but grant-hash cardinality ≈ user count, so cache hit rate collapses at 10k users; every miss is a filtered HNSW scan whose recall/latency behavior under high-cardinality `aclRefs &&` filters is unspecified (§8.3, and DatabasePlan §11 claims filtering happens "before similarity ranking", which pgvector HNSW does not actually do). | RAG-06: specify filtered-HNSW strategy (iterative scans / ef adaptation / pgvector version floor); read replicas + Qdrant payload-indexed filters as scale-out. |
| 500 tenants × per-tenant embedding models | **Degrades** | §5 creates an index space per `(embeddingModelId, dimension)`; per-tenant overrides (FR-101) at 500 tenants can mean hundreds of index spaces/tables, each with its own HNSW — operational sprawl, memory planning per §15 becomes per-space. | Constrain to a curated model catalog per deployment; document space-count ceiling. Acceptable with ops guidance (RAG-09/RAG-08). |
| Model change re-embed at 10M chunks | **Degrades** | §5 dual-space cutover at 99% completeness is the right design. At §15 throughput (1–3k chunks/min/GPU), 10M chunks = 2.5–5 GPU-days, with double vector storage during the window. Neither the time nor the storage doubling is stated. | RAG-08: publish the envelope + admin pre-flight estimator; parallel embedding workers already possible (§3.1 batching). |
| High-churn corpus (deletes/updates at 10× volume) | **Degrades** | Tombstones hide chunks at query time immediately (§10) — correct. But hard-delete after 30 days leaves dead HNSW entries until VACUUM; pgvector HNSW never shrinks and degrades under sustained churn. No vacuum/rebuild guidance exists (§15 covers only initial tuning). | RAG-09: HNSW maintenance cadence + bloat metric. |
| Air-gapped / local-LLM-only | **Holds** | Bundled `bge-small-en-v1.5` ONNX (§5), local re-ranker option (§11), pgvector in-DB, zero egress. This is genuinely solid — one of the strongest parts of the document. | None needed. |
| Strict isolation, 50→500 tenants, single index per model | **Holds at committed envelope, conditional at 10×** | pgvector: RLS + SPI-injected tenant filter (§9) is layered. Qdrant: RAGArchitecture §5/§6 says one collection per model shared across tenants with an SPI payload filter — but SecurityModel §5 says "separate Qdrant collections per tenant". These are different isolation architectures; the shared-collection variant has exactly one enforcement layer (the SPI filter). | RAG-01 resolves the contradiction before any Qdrant work; per-tenant collections (or at least per-tenant shards) also fix noisy-neighbor at 10×. |

## 3. Strengths

1. **Honest untrusted-input stance with layered containment.** §3.3 does not pretend screening is a boundary: containment rests on read-only tools, staged `writeArtifact`, and mandatory Validation Agent checks (AgentArchitecture §4 rule 4/6, §6.2). That is the correct architecture — screening as signal, capability sandbox as boundary.
2. **Retrieval-time re-screening** (§3.3) closes the "rules updated after indexing" gap most designs miss.
3. **Citation chain is near-complete.** `retrievalAuditId` is plumbed ragSearch → `contextBundle` (AgentArchitecture §5.14) → machine-readable citation (§12) → audit row (§13), with an acceptance criterion (§17). This is genuinely good; only the Validation-side binding check is missing (RAG-11).
4. **Error contract distinguishing "no matches" from "retrieval failed"** (§11) prevents fabrication-by-silence — a subtle failure mode explicitly handled.
5. **Score computation over the permitted universe only** (§8.3 push-down, not post-filter) prevents rank-position side channels across permission boundaries.
6. **Group expansion at ACL-sync time, set-lookup at query time** (§8, identity mapping note) is the right complexity split; unmapped principals → public-only (never broad) is correct deny-by-default.
7. **Dual-space model cutover with completeness threshold** (§5) and checkpointed indexing jobs (§3.1) are implementation-grade.
8. **`eip.rag.injection_flags` and `eip.rag.acl.freshness_seconds` routed as security signals, not ops noise** (§15) — the staleness window is at least treated as a security control.
9. **Air-gap story** (§5, bundled model with license and prefix-template detail) is complete and specific.

## 4. Weaknesses

1. **Injection screening is pattern-level only; no normalization pipeline.** §3.3 lists literal patterns ("ignore previous instructions"). Nothing specifies Unicode NFKC + confusable folding before matching (homoglyph bypass), detection of base64/hex-encoded instruction payloads, or non-English instruction patterns. §3 Clean stage normalizes "whitespace and encodings" for indexing, not adversarially for the screen.
2. **Per-chunk screening is trivially bypassed by multi-chunk assembly.** Instructions split across chunk boundaries (each fragment innocuous) reassemble in the agent context. Nothing screens the *assembled* context bundle; §3.3 screens chunks individually.
3. **Metadata fields are an unscreened injection channel.** Confluence heading paths are "prefixed into chunk text" (§4) and citation entries carry `title` and `headingPath` into contexts and rendered outputs (§12). The screening spec (§3.3) covers chunk content; titles, heading paths, and author fields — attacker-controllable in the source tool — are not named as screened or sanitized inputs.
4. **Delimiter spoofing unaddressed.** §3.3's provenance-delimited blocks work only if content cannot close its own block. SecurityModel §8(b) says "strip markup that mimics prompt structure", but neither doc specifies unguessable (per-run randomized) delimiters or escaping of delimiter sequences inside retrieved content.
5. **Single documented enforcement layer for intra-tenant ACLs.** §8 specifies push-down filtering only. SecurityModel §4 layer 3(c) asserts a "result-side re-check before chunks enter the prompt" — that layer does not exist in RAGArchitecture. If the SPI filter has a bug (wrong grant expansion, operator error in `aclRefs`), there is no second check and no test asserting one. For tenant isolation there are two layers (RLS + SPI); for permissions there is one.
6. **Filtered-HNSW behavior unspecified.** §8.3 mandates in-query filter push-down; DatabasePlan §11 claims filtering happens "before similarity ranking". pgvector HNSW filters *during/after* graph traversal — under selective ACL filters this yields either recall collapse or latency blowup unless iterative scans / ef adaptation are specified. No pgvector version floor, no strategy, no G5 evidence requirement tied to this.
7. **ACL sync mechanics at large-wiki scale unspecified** (see scenario table): the ≤15 min guidance is asserted without a mechanism that can meet it on a 500k-page space.
8. **Re-embed and migration cost envelopes undocumented** (RAG-08), **HNSW churn maintenance undocumented** (RAG-09), **Qdrant migration semantics ambiguous** — §6 calls it an "offline re-index"; it should be a vector *copy* (embeddings are model-determined), which changes the migration window by orders of magnitude. Undefined = an implementer may re-embed 10M chunks unnecessarily, or assume copy when re-rank models differ.
9. **ADR-005 trigger has no owner or operational metric.** §6 gives criteria (~20M chunks, p95 miss, OLTP contention) but no named decision owner, no dashboard/alert bound to the trigger, and ArchitectureOverview ADR-005 says 10M+. At 10× scale this decision *will* arrive; today nobody is on the hook to make it.
10. **Validation Agent does not bind citations to audit rows.** §12 checks: chunkId ∈ run results, sourceUrl registered, claims cited. A fabricated `retrievalAuditId` paired with a legitimate chunkId passes validation — weakening the "independently verifiable" claim of §12.

## 5. Contradictions

| # | Side A | Side B | Impact |
|---|---|---|---|
| C1 | RAGArchitecture §5/§6: Qdrant = "one collection per model", tenant isolation via SPI payload filter | SecurityModel §5 (Multi-Tenancy table): "separate Qdrant collections per tenant when Qdrant is used" | Different isolation architectures with different blast radii; shared collection has one enforcement layer. Must be resolved before any Qdrant implementation (RAG-01). |
| C2 | RAGArchitecture §3.3: flagged content "is still indexed … but flagged"; "Flags do not by themselves block retrieval" | SecurityModel §8: "Ingested documents flagged by injection heuristics are **quarantined from the RAG index** pending review" | Opposite behaviors for the same event. An implementer will pick one ad hoc; the quarantine variant also creates an availability/DoS vector (poison a competitor team's docs to suppress them). Decide and align (RAG-02). |
| C3 | RAGArchitecture §7: typed columns `aclRefs text[]`, per-model tables `rag_chunk_embedding_<modelKey>` (§5 dimension handling) | DatabasePlan §11: `ai.rag_chunk` with single `embedding` column, `metadata jsonb`, `acl_hash`, GIN-on-jsonb filtering | Two different physical schemas for the same subsystem; per-model index spaces are impossible with one fixed-dimension column. Flyway migrations will follow one doc and silently invalidate the other (RAG-05… tracked as RAG-14). |
| C4 | ArchitectureOverview ADR-005: escape hatch at "10M+ chunks" | RAGArchitecture §6: stay on pgvector until "~20M chunks" | 2× disagreement on the platform's most important capacity trigger (RAG-10). |
| C5 | SecurityModel §4.3(c): "result-side re-check before chunks enter the prompt" | RAGArchitecture §8: no result-side recheck specified anywhere | Defense-in-depth asserted in the security contract but absent from the implementing design (RAG-03). |

## 6. Missing decisions

An implementer will be forced to decide ad hoc:

1. Result-side ACL/tenant recheck: exists or not, and at which layer (SPI vs retrieval API vs agent runtime) (RAG-03).
2. Filtered-HNSW strategy: pgvector version floor (iterative scans landed in 0.8.x), `ef_search` adaptation policy under selective filters, behavior when the caller's grant set is very large or very small (RAG-06).
3. Qdrant tenancy topology (per-tenant collection vs shared + filter) and whether migration copies vectors or re-embeds (RAG-01, RAG-10).
4. Injection-screen normalization set (NFKC, confusables, encoded payloads, languages) and whether the screen runs over assembled context bundles and metadata fields (RAG-04).
5. Delimiter scheme for provenance blocks: static markers vs per-run nonce delimiters vs content escaping (RAG-05).
6. Quarantine vs flag-and-serve for injection-flagged documents (RAG-02).
7. ACL restriction-change detection per source (webhook? sweep? hybrid?) and behavior when sweep duration exceeds the configured staleness threshold (RAG-07).
8. HNSW maintenance: vacuum/rebuild cadence, bloat metric, reindex-concurrently runbook (RAG-09).
9. Who decides the Qdrant migration and against which dashboard metric (RAG-10).
10. Physical schema of record: RAGArchitecture §7 vs DatabasePlan §11 (RAG-14).

## 7. Risks

**Scalability.** Filtered-HNSW recall/latency under ACL filters is the primary retrieval-scale risk at the *committed* envelope, not just at 10× (weakness 6). FTS leg remains in Postgres at any vector-store choice — a corpus-sized ceiling with no documented scale-out. Per-principal cache keying makes cache efficacy inversely proportional to user count.

**Security.** Screening bypass via homoglyphs/encoding/multi-chunk assembly/metadata is feasible today as specified; containment (capability sandbox + validation) is the real boundary and it is well-built — but flag telemetry (`eip.rag.injection_flags`), which security teams will treat as coverage, will systematically under-report these bypass classes. Delimiter spoofing could unwrap the provenance framing itself.

**Tenant isolation.** Solid on pgvector (RLS + SPI). Contradiction C1 leaves the Qdrant path with an undefined isolation architecture.

**Data consistency.** Qdrant is eventually consistent with Postgres metadata via outbox (§6): a chunk hard-deleted in Postgres but not yet in Qdrant returns a vector hit whose metadata lookup fails — behavior unspecified (should fail closed, dropping the hit). Tombstone semantics for Qdrant (payload flag vs point delete) unspecified.

**AI safety.** Retrieval poisoning (documents crafted to rank highly for anticipated queries — "SEO for RAG") is unaddressed; MMR and re-ranking mitigate incidentally, not by design. Acceptable for MVP given citation + validation guardrails, but should be a named, accepted risk (RAG-13).

**Operational.** Days-long re-embed windows with doubled storage will surprise operators (RAG-08); HNSW bloat under churn will surface as slow, unexplained latency drift (RAG-09).

**Implementation.** Schema contradiction C3 will produce rework in Flyway migrations if not resolved before Phase 3 tasks are cut.

**Governance.** Coverage is good: SecurityChecklist item 73 makes untrusted treatment a BLOCKER, A4 mandates the RAG isolation suite with no waiver, TestingChecklist §8 extends the isolation suite on retrieval-filter changes, QualityGatePolicy CC-5 forces the AI eval subset. Two gaps: (a) no checklist item asserts *intra-tenant permission* (ACL) leakage tests — A4 and E7 are tenant-isolation-focused; SecurityModel §15's Team A/B criterion needs a home in the automated suite; (b) no gate or register owns the ADR-005 capacity trigger (RiskManagementPolicy has no corresponding risk entry).

## 8. Required fixes

| ID | Severity | Target document(s) | Description | Status |
|---|---|---|---|---|
| RAG-01 | MANDATORY | RAGArchitecture §5/§6; SecurityModel §5 | Resolve Qdrant tenancy contradiction (C1). Decide one topology — recommended: per-tenant Qdrant collections (matching SecurityModel §5) with SPI-enforced collection routing; if shared-per-model is kept, SecurityModel §5 must be amended and a mandatory second enforcement layer (result-side tenant assert) specified. State the decision in both documents identically. | APPLIED (2026-07-06) |
| RAG-02 | MANDATORY | RAGArchitecture §3.3; SecurityModel §8 | Resolve quarantine-vs-flag contradiction (C2). Recommended: adopt RAGArchitecture's flag-and-serve for heuristic hits, reserve quarantine for a defined high-confidence class, and rewrite SecurityModel §8 sentence "Ingested documents flagged by injection heuristics are quarantined…" to match, including the admin review queue for quarantined docs. | APPLIED (2026-07-06) |
| RAG-03 | MANDATORY | RAGArchitecture §8, §17 | Add the result-side recheck layer asserted by SecurityModel §4.3(c): before hits leave the retrieval API, re-verify `tenantId` and `aclRefs ∩ callerGrantKeys ≠ ∅` per hit against Postgres (RLS-protected `rag_chunk`), fail closed by dropping violating hits and emitting a security alert metric (`eip.rag.acl.recheck_violations`). Add an acceptance criterion: an injected SPI-filter fault (test seam) must produce zero leaked hits and a nonzero recheck-violation count. | APPLIED (2026-07-06) |
| RAG-04 | RECOMMENDED | RAGArchitecture §3.3 | Specify the screening pipeline: NFKC + Unicode-confusable folding before pattern matching; encoded-payload heuristics (base64/hex runs decoded and re-screened); screening applied to metadata fields entering prompts (title, headingPath, author) and to the assembled context bundle (multi-chunk assembly defense), not only per-chunk. State that flag metrics under-report unscreened classes until this lands. | SCHEDULED (pre-Sprint-3) |
| RAG-05 | RECOMMENDED | RAGArchitecture §3.3; AgentArchitecture §4 | Specify delimiter-spoofing defense for provenance blocks: per-run randomized delimiter nonces, plus escaping/neutralization of any delimiter-like sequences inside retrieved content. Add an adversarial fixture (chunk containing the closing delimiter + injected instructions) to the security regression corpus (SecurityModel §13 AI item). | SCHEDULED (pre-Sprint-3) |
| RAG-06 | RECOMMENDED | RAGArchitecture §8, §15; DatabasePlan §11 | Define the filtered-ANN strategy: minimum pgvector version with iterative index scans; `ef_search` escalation policy when the post-filter candidate count falls short of k; documented behavior for extreme grant-set cardinalities; correct DatabasePlan §11's "filtered before similarity ranking" claim to describe actual HNSW filter semantics. Require G5 evidence (PerformanceChecklist) for retrieval at envelope scale with realistic ACL selectivity (1%, 10%, 90%). | SCHEDULED (pre-Sprint-3) |
| RAG-07 | RECOMMENDED | RAGArchitecture §8; ConnectorFramework (ACL sync section) | Specify per-source ACL restriction-change detection mechanics and their ceilings: which sources support restriction webhooks/events, sweep cost model for those that do not (state explicitly that a 500k-page full restriction sweep cannot meet a 15-minute cadence), and make "drop stale-beyond-threshold source from retrieval" (§16) the documented default for revocation-sensitive spaces above a stated page-count ceiling. | SCHEDULED (pre-Sprint-3) |
| RAG-08 | RECOMMENDED | RAGArchitecture §5, §15 | Document the model-change re-embed envelope: time formula (chunks ÷ throughput per §15), transient storage doubling during dual-space operation, and an admin-UI pre-flight estimate (chunk count, ETA, extra storage) required before triggering a tenant re-embed. | SCHEDULED (pre-Sprint-3) |
| RAG-09 | RECOMMENDED | RAGArchitecture §15; OperationsGuide | Add HNSW maintenance guidance under churn: autovacuum tuning for embedding tables, `REINDEX CONCURRENTLY` cadence trigger (bloat/dead-tuple metric threshold), and an `eip.rag.index.bloat` gauge with alerting. | SCHEDULED (pre-Sprint-3) |
| RAG-10 | RECOMMENDED | RAGArchitecture §6; ArchitectureOverview ADR-005 | Align the migration trigger (pick one chunk threshold; recommend 10M as review trigger, 20M as hard ceiling), name the decision owner (owning architect of `eip-ai` per ModuleOwnership, via ADR process), bind the trigger to dashboard metrics (`chunk count per cluster`, retrieval p95, OLTP contention), and state that migration copies vectors (no re-embedding) when the embedding model is unchanged. | SCHEDULED (pre-Sprint-3) |
| RAG-11 | RECOMMENDED | RAGArchitecture §12; AgentArchitecture §5.16 | Extend the Validation Agent citation check with binding: each citation's `(chunkId, retrievalAuditId)` pair must match a `rag_retrieval_audit` row of the same run whose `returnedChunkIds` contains that chunkId. Add to §17 acceptance criteria. | SCHEDULED (pre-Sprint-3) |
| RAG-12 | ACCEPTED-RISK | RAGArchitecture §8 | ACL staleness window between source revocation and next sync (already documented §8.4 with ≤15 min guidance and freshness alerting as security control). Accept for MVP with the stated bound; revisit trigger: any tenant requiring revocation SLO < sync interval, or any source above the RAG-07 page-count ceiling. | ACCEPTED (MVP) |
| RAG-13 | ACCEPTED-RISK | RAGArchitecture §3.3 or §14 | Retrieval poisoning (adversarial ranking optimization) and FTS-leg scale ceiling: document both as named accepted risks. Poisoning mitigated incidentally by MMR/re-rank/citations; revisit when RAG serves decision-critical outputs beyond cited narratives. FTS ceiling revisit trigger: corpus > 20M chunks or FTS query p95 > 150 ms. | ACCEPTED (MVP) |
| RAG-14 | MANDATORY | DatabasePlan §11; RAGArchitecture §7 | Reconcile the physical schema (C3): one source of record for chunk/embedding tables. Recommended: DatabasePlan adopts RAGArchitecture's model — typed filter columns (`tenant_id`, `acl_refs text[]` with GIN, `deleted_at`), per-model embedding tables `rag_chunk_embedding_<modelKey>` — and drops the single fixed-dimension `embedding` column and `acl_hash` design, or explicitly maps how `acl_hash` implements `aclRefs`. Blocks Flyway migration work otherwise. | APPLIED (2026-07-06) |

## 9. Area verdict & conditions

**READY WITH CONDITIONS.** Mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items. The RAG design is one of the more mature documents in the set: the trust-boundary philosophy is right (screening as signal, capability sandbox as boundary), the citation/audit chain is nearly airtight, and the air-gap story is complete. It is not implementable as-specified because three contradictions leave the isolation architecture, the injection-response behavior, and the physical schema each with two competing definitions, and the permission defense-in-depth asserted by the SecurityModel is missing from the implementing design.

Conditions gating readiness (must land in source docs before Phase 3 RAG tasks are cut): **RAG-01, RAG-02, RAG-03, RAG-14**.
RECOMMENDED fixes RAG-04–RAG-11 due before Sprint 3 / next design iteration; RAG-12, RAG-13 accepted with stated revisit triggers.

## 10. Inviolable principles

1. Retrieved content is data, never instructions — no retrieved or metadata-derived text may alter tool allow-lists, budgets, system prompts, or agent control flow (SecurityModel §8c).
2. Tenant isolation is enforced by at least two independent layers on every retrieval path (RLS/collection + SPI filter), and `tenantId` is never caller-suppliable.
3. Permission filtering is push-down plus result-side recheck; a single filter bug must never equal a leak, and scores are computed only over the permitted universe.
4. The caller's principal — never a service superuser — is the retrieval principal; unmapped identities resolve to public-only, never broad, grants.
5. Every retrieval is audited, and every citation is bound to an audit row (`retrievalAuditId`); no un-audited path to chunk content exists.
6. Deleted content disappears from retrieval on the deletion event (tombstone), regardless of GC schedule.
7. Retrieval failure is explicit — a typed error, never an empty result masquerading as "no matches"; agents state "sources unavailable" rather than fabricate.
8. Secrets detected at ingestion are redacted before embedding — nothing embedded can be un-redacted later.
9. All RAG capabilities function air-gapped with bundled local models; no feature may introduce mandatory egress.
10. Embedding-model and vector-store changes go through golden-set regression gating before cutover; quality regressions do not ship silently.
