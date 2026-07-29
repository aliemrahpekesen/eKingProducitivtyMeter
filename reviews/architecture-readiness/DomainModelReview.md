# Domain Model Review — Architecture Readiness (Red Team)

> Reviewer: Principal Data Architect · Date: 2026-07-06 · Verdict: **READY WITH CONDITIONS** — mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items.

## 1. Scope & reviewed documents

Primary sources (deep-read):

- [docs/architecture/DomainModel.md](../../docs/architecture/DomainModel.md) — the review subject
- [docs/engineering/DatabasePlan.md](../../docs/engineering/DatabasePlan.md) — physical realization of the model
- [docs/architecture/DataFlow.md](../../docs/architecture/DataFlow.md) — flows that exercise the model
- [docs/product/PRD.md](../../docs/product/PRD.md) — FR/NFR envelope (esp. FR-034–FR-036, FR-061, FR-062, NFR-001–NFR-004)
- [docs/engineering/EventModel.md](../../docs/engineering/EventModel.md) — envelope, event taxonomy, examples

Consulted: [docs/architecture/ArchitectureOverview.md](../../docs/architecture/ArchitectureOverview.md) (§1 D3 scale envelope, §7 ADRs), [docs/engineering/ConnectorFramework.md](../../docs/engineering/ConnectorFramework.md) (sync/webhook semantics, connector quirks), [docs/architecture/SecurityModel.md](../../docs/architecture/SecurityModel.md) §7 (PII/retention), [CLAUDE.md](../../CLAUDE.md) and [engineering-operating-system/](../../engineering-operating-system/) gates (QualityGatePolicy, PerformanceChecklist, SecurityChecklist).

Calibration: committed envelope per PRD §6 / ArchitectureOverview §1 D3 (≥10k repos, ≥1M WorkItems, 100M canonical records, ≥50 tenants, 100k events/h); extreme scenarios stressed at ~10× that (10M+ Jira issues, 100k repos, 1M deployments, 500 teams, 10k users, 100+ tools).

## 2. Extreme-scenario assessment

| Scenario | Verdict | Reasoning (citations) | Mitigation path |
|---|---|---|---|
| 10M+ Jira issues in one deployment, with routine project moves/re-keys | **Breaks** | DomainModel §12.1 maps Jira `key` (e.g. `PROJ-1234`) to `ExternalRef.externalId`; §2.2 even cites `PROJ-1234` as the example "Native ID". A Jira issue moved between projects changes its key but keeps its immutable numeric `id`. Under the §2.2 resolution algorithm (unique on `externalId`), a moved issue misses the lookup and a **duplicate WorkItem** is created — history splits, flow metrics double-count. At 10M issues, moves are routine, not edge cases. | DMR-01: key = `externalKey` (mutable, aliased); identity = immutable source `id`. Migration of this choice after data exists is expensive (unique key is embedded in the hottest ingestion path, DatabasePlan §4). Must be fixed pre-implementation. |
| 100k repositories (renames/transfers) | Degrades | GitHub `node_id` as externalId (§12.2) is rename-stable — good. But §2.2 step 3 correlates `Repository` "by normalized clone URL when a repo appears via both GitHub and a CI connector" — clone URLs change on rename/org transfer, silently forking repo identity across connectors. EventModel §4.3 PR example even uses `"acme/payments#912"` (name-based) as externalId, contradicting §12.2. | Rename-alias handling on Repository ExternalRefs; fix EventModel examples (DMR-01 scope). |
| 1M deployments — WorkItem↔Commit↔PR↔Deployment correlation edges | **Breaks (unimplementable as documented)** | FR-036 (P0, Phase 1) requires persisting correlation edges *with provenance*. DomainModel §6 defines PR↔WorkItem as "RELATES_TO edges", §7 shows `Release }o--o{ WorkItem`, §4 shows `Initiative "1" --> "*" WorkItem` — but **no entity, attribute, or table stores any of these edges**. `Dependency` (§5) is WorkItem↔WorkItem only; DatabasePlan §2/§3 has no link table. At 2.5M PRs and 100M+ commits the edge set is tens of millions of rows and dominates several query paths — it cannot be improvised ad hoc. | DMR-03: define the link table(s), uniqueness, provenance, indexes, and volume estimate. |
| 500 teams × custom workflows — WorkflowState mapping | **Breaks** | DomainModel §5 anchors `WorkflowState` to `boardId FK (req)` and §12.1 maps "Status name → WorkflowState per board". In Jira, statuses belong to project workflows; boards are *views* — an issue can sit on zero or several boards. A board-less issue (ubiquitous in real Jiras) has no legal `currentStateId` (required FK), and an issue on two boards has an ambiguous one. This blocks the Phase 1 Jira normalizer at any scale, and at 500 teams the board-multiplied state duplication also inflates mapping config (FR-061) needlessly. | DMR-04: re-anchor WorkflowState to workflow/project scope; Board carries a column→state mapping. |
| 10k users across 100+ tools — Member merge/split | Degrades | §2.3 is a solid design *on paper* (anchor OIDC subject, verified-email merge, commit-email graph, manual override, reversible split "because facts reference MemberIdentity"). But the entity tables contradict it: `WorkItem.assigneeMemberId`, `PullRequest.authorMemberId`, `CodeReview.reviewerMemberId` (§5, §6; DatabasePlan §3) reference **Member directly** — only `Commit.authorIdentityId` goes through MemberIdentity. A split therefore *does* require rewriting historical facts, contradicting §2.3. At 100+ tools with heuristic merges, splits will happen. | DMR-02 (choose attribution model), DMR-10 (merge governance: thresholds, collisions, bulk re-attribution). |
| Deep hierarchy: Epic under Initiative under Roadmap | Degrades | Containment is closed at EPIC (§5.2: `EPIC ← FEATURE ← STORY ← TASK`). Jira Advanced Roadmaps and Azure DevOps have configurable levels *above* Epic; §12.1's per-tenant type override map handles custom type names but not extra levels. Initiative exists (§4) but the Initiative↔Epic link has no storage (see edge finding). | DMR-09 (above-Epic mapping policy) + DMR-03 (link storage). |
| Source deletions at scale (tombstones) | Degrades | DataFlow §9 says "Soft-delete via source tombstones"; EventModel §4.2 has `workitem.deleted`. But nothing specifies how a *polled* connector detects deletions: incremental sync by `updatedSince` never sees them, and ConnectorFramework §"Full vs incremental" explicitly says a forced re-sync runs "without deleting normalized data". Only webhook sources (ConnectorFramework Jira: "issue … deleted") ever emit tombstones. At 10M issues, undetected deletions accumulate as phantom WIP and corrupt flow metrics. | DMR-06: reconciliation sweep against `ExternalRef.lastSeenAt` per stream. |
| 500 teams across many timezones — flow-metric calendars | Degrades | DomainModel §14 rule 3 (all UTC) is right for storage, but cycle/lead/blocked time (WorkItemTransition §5) has no calendar semantics; FR-061 mentions a per-tenant "working calendar", yet Team (§4) has no timezone and no calendar entity exists. A tenant-level calendar is wrong for a 500-team multinational. | DMR-07: Team.timezone + calendar model; declare wall-clock vs business-hours per metric. |
| 50→many tenants; strict isolation | Holds | `tenantId` on every entity (§1 principle 2), enforcement via RLS (DatabasePlan §5) and defense-in-depth write check (§14 rule 1). Identity, events, and refs are all tenant-prefixed. No model-level obstacle to more tenants. | — |
| 100M+ canonical rows in `work_item`-family tables | Degrades gracefully | Single-table supertype (§1 principle 3) with 2 KB rows (DatabasePlan §9) reaches ~200 GB at 100M items. Feasible on NVMe; partitioning is not foreclosed (tenant LIST or time RANGE both possible because every access path is tenant+time keyed, DatabasePlan §4). | DMR-11 accepted risk with documented trigger. |

## 3. Strengths

- **Internal UUIDv7 identity with explicit provenance** (§2.1–§2.2) is exactly right: source identity lives only in ExternalRef; internal IDs are stable across re-syncs, time-ordered, pagination-friendly. The `(tenantId, sourceSystem, sourceInstance, entityType, externalId)` unique key correctly includes `sourceInstance` — the "enterprises run multiple Jiras" reality most models miss.
- **The WorkItem supertype decision** (§1 principle 3, §5) avoids a table-per-type explosion and matches the FR-034 vocabulary 1:1; the type-hierarchy invariants (§5.2) are concrete and testable.
- **Append-only fact tables** (`WorkItemTransition` §5, `Commit` §6 "Immutable") with derived-field ownership pinned to `eip-analytics` (§14 rule 5) — clean CQRS discipline; connectors can never fight analytics over derived state.
- **Cross-context soft references** (§14 rule 6, DatabasePlan §1 FK rule) keep the Modulith extraction path (ArchitectureOverview §2.2) honest at the data layer.
- **Anti-surveillance is structural, not aspirational**: §1 principle 5, Commit attribution "via MemberIdentity, never raw email in analytics" (§6), backed by FR-057/NFR-071 and CLAUDE.md law 6 as release-blocking.
- **State machines with monotonic-timestamp invariants** (§5.1, §9.1) give the metric engine deterministic ground truth (MTTR only on CLOSED, `resolvedAt` iff DONE/CANCELLED).
- **The reversibility idea in §2.3** (facts → MemberIdentity → Member) is the correct design for merge/split at scale — it is only the entity tables that fail to implement it (see Weaknesses).

## 4. Weaknesses

1. **Mutable source keys used as identity** — DomainModel §2.2 (example `PROJ-1234`) and §12.1 (`key` → `externalId`). Jira keys change on project move; identity must be the immutable numeric issue `id`. Consequence at scale: duplicated WorkItems, split transition history, double-counted throughput. Same class of issue: EventModel §4.3 PR example uses `acme/payments#912`; Repository clone-URL correlation (§2.2 step 3) breaks on rename.
2. **No storage for correlation edges** — §6 invariants ("PR↔WorkItem links are RELATES_TO edges"), §7 (`Release ships WorkItem`), §4 (Initiative→epics) name relationships that exist nowhere as attributes or tables (checked against DatabasePlan §2/§3). FR-036 is P0 Phase 1. Also undecided: are Commit↔WorkItem links materialized rows (potentially ~10⁸ at scale) or derived at query time?
3. **Member attribution contradicts the split model** — §2.3 claims facts reference MemberIdentity; §5/§6 and DatabasePlan §3 use direct Member FKs everywhere except Commit. Split is destructive as modeled.
4. **WorkflowState owned by Board** — §5 (`boardId FK req`) mis-models every major tracker; board-less items cannot be normalized (required `currentStateId`), multi-board items are ambiguous.
5. **Normalized 7-value status underdefined** — §5.1's machine has NEW/READY/IN_PROGRESS/BLOCKED/IN_REVIEW/DONE/CANCELLED, but WorkflowState carries only a 3-value `category` (§5). The derivation of READY vs NEW and IN_REVIEW vs IN_PROGRESS from source states is unspecified; §12.1 maps only `statusCategory` → category. Every implementer will invent different rules; FR-061 says this must be tenant-configurable.
6. **Deletion detection for polled sources unspecified** (see scenario table). Additionally DatabasePlan §10 hard-deletes soft-deleted rows after 90 days — the effect on metric drill-down cohorts (FR-067) and on `work_item_transition` rows (append-only, no `deleted_at`, would be orphaned) is unaddressed.
7. **No calendar/timezone model** for flow metrics despite FR-061 naming a "working calendar" as tenant config.
8. **UUID-array references** — `Service.repositoryIds UUIDv7[]`, `Environment.serviceIds UUIDv7[]` (§9, §7): not join-indexable, no referential validation, awkward at 1,000+ K8s namespaces / services. (`Board.columnOrder` is fine — it is ordering state, not a relationship.)
9. **Sprint bound to a single Project** (§5 `projectId FK req`; invariant "a WorkItem may reference only Sprints of its own Project") — Jira sprints are board-scoped and routinely contain issues from multiple projects; the invariant will reject real data.

## 5. Contradictions

| # | Side A | Side B | Impact |
|---|---|---|---|
| 1 | DomainModel §2.3: "facts (commits, work items) reference `MemberIdentity` and derive `Member` through it", merging "reversible … without rewriting historical facts" | DomainModel §5 (`assigneeMemberId`, `reporterMemberId`), §6 (`PullRequest.authorMemberId`, `CodeReview.reviewerMemberId`); DatabasePlan §3 same columns | Split/merge reversal is impossible as physically modeled; the model's own headline claim is false |
| 2 | DomainModel §12.2: GitHub `node_id` → `ExternalRef.externalId` | EventModel §4.3 `scm.pr.merged` example: `"externalId": "acme/payments#912"` | Contract-test fixtures (EventModel §15 point 3) would enshrine a rename-unstable identity |
| 3 | DomainModel §2.2: `externalId` = "Native ID in the source" with stability implied by the resolution algorithm | Same sentence's example `PROJ-1234` and §12.1 mapping `key` → externalId — Jira keys are mutable | Identity duplication on project move (see §2 row 1) |
| 4 | DomainModel §4 diagram: `Initiative "1" --> "*" WorkItem : epics` | No attribute on WorkItem or Initiative carries this link; absent from DatabasePlan | Roadmap-to-delivery traceability (PRD §3 PMO persona) has no data path |
| 5 | DomainModel §5: WorkflowState requires `boardId`; WorkItem requires `currentStateId` | Source-tool reality: issues exist without boards; ConnectorFramework Jira stream set syncs issues project-wide, not per board | Phase 1 normalizer cannot satisfy both constraints |

## 6. Missing decisions

An implementer will be forced to decide ad hoc:

1. **Where correlation edges live** (table name, schema owner, uniqueness, provenance fields), and whether Commit↔WorkItem links are materialized or derived (FR-036).
2. **Jira identity field** (`id` vs `key`) and the key-alias strategy for correlation parsing after moves (old keys still appear in old commit messages / PR titles).
3. **Member re-attribution semantics on merge/split** — re-point FKs in bulk (how? audited? bounded?) or read-time derivation.
4. **Derivation rules for the 7-value normalized status** from source states + 3-value category, and their per-tenant configuration shape (FR-061).
5. **Deletion reconciliation policy** — sweep cadence, `lastSeenAt` staleness threshold, whether reconciliation deletion is distinguishable from archive.
6. **Business-time semantics per flow metric** — wall-clock vs working-calendar, and where the calendar lives (tenant vs team).
7. **Above-Epic hierarchy mapping** — Initiative vs custom WorkItem types for Jira Advanced Roadmaps levels.
8. **Multi-board membership** — which board's column state drives `currentStateId` when several apply.

## 7. Risks

**Data consistency.** (a) Duplicate identities from mutable external keys (highest risk — silently corrupts every downstream metric; detection is hard because both duplicates look valid). (b) Phantom-alive entities from undetected source deletions inflate WIP/aging metrics. (c) Member merge errors at 100+ tools mis-attribute facts; with direct Member FKs, a wrong merge is not cleanly reversible (contradiction #1).

**Scalability.** Correlation-edge cardinality at 1M deployments / 100M commits is unbudgeted (not in DatabasePlan §9); if edges are materialized per-commit they become a top-3 table with no design. `core.external_ref` itself (~1 row per entity per source, i.e. > total canonical row count) is also absent from §9 sizing despite being "the hottest ingestion path" (DatabasePlan §4) — see DAR-04 in the companion review.

**Implementation.** WorkflowState/Board mis-anchoring and the underdefined status derivation sit directly on the Phase 1 critical path (PRD §10 Phase 1 exit: "normalized model v1 … WorkItem + ExternalRef mapping"); discovered during implementation they force either schema rework or divergence from docs (violating CLAUDE.md law 1).

**Tenant isolation.** No model-level gaps found: tenant scoping is uniform, and governance genuinely enforces it (SecurityChecklist items 34–37 make missing RLS a BLOCKER; RLS policy changes are CC-1). Solid.

**AI safety.** Attribution via MemberIdentity keeps raw emails out of analytics (§6); pseudonymization option exists (SecurityModel §7). Adequate at model level; erasure mechanics are a data-architecture issue (DAR-03).

## 8. Required fixes

| ID | Severity | Target document(s) | Description | Status |
|---|---|---|---|---|
| DMR-01 | MANDATORY | DomainModel §2.2 + §12.1; EventModel §4.3 | Make `ExternalRef.externalId` the *immutable* native id: Jira numeric issue `id` (key → `externalKey`); fix §2.2's `PROJ-1234` example; add a key/alias-change rule (on key change: update `externalKey`, retain prior keys as aliases used by §2.2 step-3 correlation parsing); for Repository, correlate by immutable repo id where available, clone-URL only as fallback with rename-alias handling; correct EventModel §4.3 PR example externalId to the GitHub node id | APPLIED (2026-07-06) |
| DMR-02 | MANDATORY | DomainModel §2.3, §5, §6; DatabasePlan §3 | Resolve the Member-vs-MemberIdentity attribution contradiction: either (a) change fact FKs to `*IdentityId` (assignee/reporter/author/reviewer) deriving Member at read, or (b) keep Member FKs and specify an audited bulk ReattributionJob executed on merge/split with bounded semantics — and update §2.3's reversibility claim to match the chosen design | APPLIED (2026-07-06) |
| DMR-03 | MANDATORY | DomainModel new §6.1 (or §2.4); DatabasePlan §2, §3, §9 | Specify correlation-edge storage per FR-036: a link table (e.g. `core.entity_link(tenant_id, from_type, from_id, to_type, to_id, link_type, provenance jsonb, confidence, created_at)` with uniqueness per (from,to,type)), covering WorkItem↔PR, WorkItem↔Commit (decide materialized vs derived — state the decision), Release↔WorkItem, Initiative↔Epic, Incident↔Deployment provenance; add indexes and a §9 volume row at NFR envelope | APPLIED (2026-07-06) |
| DMR-04 | MANDATORY | DomainModel §5, §12.1; DatabasePlan §3 (`work.workflow_state`) | Re-anchor WorkflowState to workflow/project scope (boardId → optional or moved to a Board→column→state mapping table); define `currentStateId` semantics for items on zero or multiple boards; update §12.1 Jira mapping accordingly | APPLIED (2026-07-06) |
| DMR-05 | RECOMMENDED | DomainModel §5.1; PRD FR-061 cross-ref | Specify the deterministic derivation of the 7-value normalized status from (source status, `statusCategory`, `isBlockedState`, per-tenant name-mapping): default rules for READY and IN_REVIEW detection plus the tenant-config override shape | SCHEDULED (pre-Sprint-3) |
| DMR-06 | RECOMMENDED | DomainModel new §2.4; ConnectorFramework sync section; DatabasePlan §10 | Specify source-deletion detection for polled connectors: per-stream reconciliation sweep comparing source key sets vs `ExternalRef.lastSeenAt`, staleness threshold, soft-delete + `*.deleted` event emission; state the drill-down/metric effect of the 90-day hard-delete of soft-deleted rows and the fate of orphaned `work_item_transition` rows | SCHEDULED (pre-Sprint-3) |
| DMR-07 | RECOMMENDED | DomainModel §4 (Team), §5; PRD FR-061 | Add `Team.timezone` and a working-calendar entity (tenant default + team override); declare per flow metric whether it is wall-clock or business-hours | SCHEDULED (pre-Sprint-3) |
| DMR-08 | RECOMMENDED | DomainModel §7 (Environment), §9 (Service); DatabasePlan §3 | Replace `Service.repositoryIds UUIDv7[]` and `Environment.serviceIds UUIDv7[]` with junction tables (`ops.service_repository`, `cicd.environment_service`) so joins are indexable and validatable at 1,000+ services/namespaces | SCHEDULED (pre-Sprint-3) |
| DMR-09 | RECOMMENDED | DomainModel §5.2, §12.1 | Define mapping for source hierarchy levels above Epic (Jira Advanced Roadmaps, ADO Epics+): per-tenant rule mapping above-Epic types to Initiative (with the DMR-03 link) or to configured WorkItem types; state the rejection/fallback behavior for unknown levels | SCHEDULED (pre-Sprint-3) |
| DMR-10 | RECOMMENDED | DomainModel §2.3 | Specify merge governance at 100+ tools: auto-merge confidence threshold, collision policy (one email ↔ multiple persons, shared/service accounts excluded), review queue for low-confidence merges, and the audited bulk re-attribution flow (ties to DMR-02) | SCHEDULED (pre-Sprint-3) |
| DMR-11 | ACCEPTED-RISK | DatabasePlan §6 (note) | `work.work_item` single-table to ~100M rows / ~200 GB is accepted for MVP; document the trigger (> ~150M rows or p95 board-query regression) and the non-foreclosed path (LIST-by-tenant or RANGE-by-`created_in_source` partitioning via expand–contract) | ACCEPTED (MVP) |
| DMR-12 | RECOMMENDED | DomainModel §5 (Sprint), §5.2 invariant, §12.1 | Relax Sprint scoping: sprints are board/origin-scoped in Jira and may contain issues from multiple projects; either make `projectId` optional with a `boardId`/origin reference or drop the "only Sprints of its own Project" invariant | SCHEDULED (pre-Sprint-3) |

## 9. Area verdict & conditions

**READY WITH CONDITIONS.** Mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items. The canonical model is well shaped — right supertype, right identity architecture in intent, right append-only fact discipline — but four defects sit on the Phase 1 critical path or foreclose later correction, and must be fixed in the source documents before implementation tasks start:

- **DMR-01** (immutable external identity) — an identity choice that cannot be migrated cheaply once 10M ExternalRefs exist.
- **DMR-02** (Member attribution model) — same class: FK direction is near-irreversible after backfill.
- **DMR-03** (correlation edge storage) — FR-036 is P0 Phase 1 and currently unimplementable without ad hoc invention.
- **DMR-04** (WorkflowState scoping) — the Phase 1 Jira normalizer cannot satisfy the current constraints against real data.

DMR-05..DMR-10, DMR-12 before Sprint 3 / next design iteration; DMR-11 accepted with documented trigger.

## 10. Inviolable principles

1. Internal identity is UUIDv7 minted by EIP; **no internal identifier is ever derived from a mutable source-tool key**, and source identity lives only in ExternalRef (DomainModel §2).
2. Every correlation edge carries provenance and is **advisory, never identity-merging** (§2.2 step 3, FR-036).
3. Fact tables (`work_item_transition`, `commit`, `audit_event`, `metric_value`) are append-only; derived fields are written only by `eip-analytics` (§14 rule 5).
4. `occurredAt` (source time) is never overwritten by `ingestedAt` (§14 rule 3); all storage is UTC.
5. Normalizers are idempotent: re-processing the same raw payload yields byte-identical canonical state (§14 rule 2).
6. Every tenant-scoped entity carries `tenantId`, checked at write time above RLS (§14 rule 1).
7. Attribution flows through MemberIdentity — raw emails never appear in analytics surfaces (§6); no individual ranking capability, ever (FR-057, NFR-071).
8. Cross-context references are soft UUIDs; no hard FKs across bounded contexts (§14 rule 6) — module extraction must remain possible.
