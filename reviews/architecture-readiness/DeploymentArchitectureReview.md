# Deployment Architecture Review

> Reviewer: Principal DevOps Architect · Date: 2026-07-06 · Verdict: **READY WITH CONDITIONS** — mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items.

## 1. Scope & reviewed documents

This review challenges the deployment topology, HA/DR posture, upgrade path, air-gap/private-CA/proxy support, and CI/CD-at-scale readiness of the Engineering Intelligence Platform (EIP) against the committed NFR envelope (PRD §6: ≥10k repos, ≥1M WorkItems, ≥50 tenants, 100k events/h; ArchitectureOverview D3: ~5,000 active engineers) and against ~10× extreme-enterprise scenarios.

Primary sources (deep-read):

- [docs/architecture/DeploymentModel.md](../../docs/architecture/DeploymentModel.md)
- [docs/infrastructure/KubernetesOpenShift.md](../../docs/infrastructure/KubernetesOpenShift.md)
- [docs/infrastructure/DockerCompose.md](../../docs/infrastructure/DockerCompose.md)
- [docs/operations/OperationsGuide.md](../../docs/operations/OperationsGuide.md)
- [engineering-operating-system/ReleaseManagement.md](../../engineering-operating-system/ReleaseManagement.md)

Consulted: [docs/product/PRD.md](../../docs/product/PRD.md) §6/§10, [docs/architecture/ArchitectureOverview.md](../../docs/architecture/ArchitectureOverview.md) (D3, scaling), [docs/architecture/ObservabilityModel.md](../../docs/architecture/ObservabilityModel.md), [docs/testing/TestingStrategy.md](../../docs/testing/TestingStrategy.md) §14, [engineering-operating-system/QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md), [engineering-operating-system/RepositoryStructure.md](../../engineering-operating-system/RepositoryStructure.md), [CLAUDE.md](../../CLAUDE.md). No application code or manifests exist yet; this reviews specifications only.

## 2. Extreme-scenario assessment

| Scenario (~10× envelope) | Holds / Degrades / Breaks | Reasoning (doc + section) | Mitigation path |
|---|---|---|---|
| 500+ teams, 10,000+ users on the API tier | **Holds** | `eip-app` is stateless, session-free, scales behind LB (DeploymentModel §6; ArchitectureOverview "API tier"); dashboards read pre-aggregated `rm_*` tables, so API load scales with users, not ingest. HPA max 6 (KubernetesOpenShift §11) is just an overlay number. | Raise HPA max + LB capacity; no design change needed. |
| 100,000+ repos, 10,000,000+ Jira issues (~100M+ canonical records) | **Degrades → breaks at multi-TB** | PostgreSQL is single-writer, "vertical first + read replicas" (DeploymentModel §6); sizing tops out at 8–16 vCPU / 64 GB / 1–4 TB (DeploymentModel §5). No sharding/partitioning-by-tenant story beyond RLS; multi-TB restore threatens the ≤4 h rebuild RTO (DeploymentModel §9) — never analyzed. pgvector at 10M+ chunks has the Qdrant SPI escape hatch (ArchitectureOverview ADR-005). | Read replicas + Qdrant offload exist; document the DB-size ceiling and a parallel-restore path (DEP-06, DEP-17). Not foreclosed, but the ceiling is silent today. |
| 1,000,000+ deployments / 1M events/h intake | **Degrades gracefully** | Kafka 3–5 brokers RF=3 (DeploymentModel §5) trivially carries the byte-rate; the constraint is the 12-partition default with max workers ≤ partition count (DeploymentModel §6; KubernetesOpenShift §11). Repartitioning is acknowledged ("resize requires planned change") but the documented procedure is unsafe as written (see Contradictions C3 / DEP-04). | Partition increase + broker add is a standard path; fix the procedure so it cannot violate per-key ordering. |
| 1,000+ customer K8s namespaces polled by connector | **Degrades gracefully** | Connector polling runs in ingestion workers scaled by KEDA on lag, max 8 (KubernetesOpenShift §11), on IO-optimized pools in Topology C (DeploymentModel §4). Ceiling is partition count and source-API rate limits, not the platform. | Raise partitions + max replicas; per-connector scheduling already exists (OperationsGuide §3.2). |
| 100+ configured integrations | **Holds, with operational friction** | One `eip.raw.<connector>` topic per connector (OperationsGuide §1.2) is fine at 100+ topics. But the claim that the egress `NetworkPolicy` "is generated from that configuration" (DeploymentModel §10) has no named owner or mechanism, and plain K8s NetworkPolicy cannot express FQDN egress (KubernetesOpenShift §6 row 11 hand-waves "CIDR/FQDN policy per site") — at 100+ endpoints this becomes a manual policy-maintenance burden (DEP-10). | Name the generation mechanism and the FQDN-capable enforcement layer (OpenShift EgressFirewall / Cilium) or the CIDR fallback. |
| Very large Confluence/wiki spaces | **Degrades gracefully** | Blobs land in MinIO, vectors in pgvector/Qdrant (DeploymentModel §1). MinIO 4-node EC:2 (KubernetesOpenShift §7) grows only by *server pools* — a real MinIO constraint the docs never mention (DEP-16). | Document pool-based expansion; Qdrant for vector scale. |
| High-volume metrics/logs/traces intake (self-observability) | **Holds** | 2-replica OTel gateway (KubernetesOpenShift §10) with Prometheus federation named for enterprise scale (DeploymentModel §4 diagram). Retention/PVC values are overlay parameters. | Scale gateway replicas; federate; optional Tempo/Loki are swappable at the Collector (ObservabilityModel §2). |
| Many tenants (500+) | **Holds until Postgres ceiling** | Application-level tenancy (RLS + keyed topics), explicitly no per-tenant namespaces (KubernetesOpenShift §2) — the right call; tenant count doesn't multiply infrastructure objects. ADR-004 justifies RLS "at 50-tenant scale" (ArchitectureOverview) — the shared-PG blast radius at 500 tenants inherits the row-2 ceiling. | Same as repos scenario: vertical PG ceiling + documented trigger. |
| Strict on-prem isolation / default-deny | **Holds** | Complete zone model and per-flow allow tables in both DeploymentModel §10 and KubernetesOpenShift §6; default-deny verified by probe pod (KubernetesOpenShift §15). Solid. | — |
| Restricted outbound / egress proxy / private CA | **Holds (one leak)** | Proxy + custom CA covered on both stacks including OpenShift cluster `Proxy` object and `inject-trusted-cabundle` (KubernetesOpenShift §5.2; DockerCompose §14); JVM truststore built from mounted PEM at entrypoint; "validation never disabled" (DeploymentModel §12). One leak: Grafana OSS defaults phone home (usage reporting + update checks), contradicting the "no other outbound traffic" claim (DeploymentModel §10) — nothing pins the disabling config (DEP-07). | One config block fixes it. |
| Fully air-gapped | **Holds — strongest area** | Digest-pinned `images.yaml`/`image-list.txt`, `oc-mirror`/skopeo procedure, offline model bundles, Kustomize-no-remote-bases, no runtime downloads, cosign verification (DeploymentModel §7; KubernetesOpenShift §14; DockerCompose §11). Offline bundle format is concrete and its air-gapped smoke-install is release-gated (ReleaseManagement §5.2, RG4). Patch cadence rebuilds and re-signs the bundle (ReleaseManagement §6). Gap: operator *installation* manifests for non-OLM vanilla K8s aren't in the bundle contents (DEP-13). | Add operator install manifests to the release archive. |
| Local/private-LLM-only | **Holds** | LLM SPI routes all tenants to Ollama/vLLM; embedding model version recorded per index forcing re-index on change (DeploymentModel §7); GPU pool optional with CPU fallback (DeploymentModel §4). | — |
| 2-DC enterprise (two data centers, no third AZ) | **Breaks as documented** | Topology C assumes 3 AZs (DeploymentModel §4); cross-site Kafka is one table cell ("per MirrorMaker2 policy", DeploymentModel §9) with no design; no CNPG replica-cluster/standby-site pattern, no MinIO site replication, no site-loss runbook, no quorum/witness discussion (a 2-site stretch cluster cannot survive DC loss with quorum intact). Worse: nothing requires the backup target to be in a different failure domain (OperationsGuide §6.1 says only "backup target reachable") — colocated backups make the ≤4 h rebuild RTO and NFR-021 RPO void on DC loss (DEP-03, DEP-05). | Active-passive second site (restore-based or async-replica) is achievable with the existing pieces; it must be designed and written down. |
| Rolling upgrade across 9 modules + workers at scale | **Holds with conditions** | Expand–contract with a version-handshake gate on contract migrations (DeploymentModel §8) and N-1 event/schema tolerance (DeploymentModel §8; ReleaseManagement §7) is genuinely strong; RG4 forces rehearsal per release. But the migrate-Job-gates-rollout mechanism is only named for Argo CD (KubernetesOpenShift §13), and two documents disagree on rollout order (Contradictions C1) — both MANDATORY (DEP-01, DEP-02). | Fix the two conditions; the underlying discipline is sound. |
| CI/CD for the monorepo at 10× codebase/team | **Degrades** | G1 compiles the full Gradle multi-module + `tsc` on every PR (QualityGatePolicy G1) inside a ≤20-min PR budget (TestingStrategy §14). No selective/affected-module build, no Gradle remote build cache decision, and the "air-gapped CI profile" (TestingStrategy §16) never says where air-gapped runners resolve Maven/npm dependencies (DEP-12). | Affected-module selection + build cache + named dependency mirror; nothing foreclosed. |

## 3. Strengths

1. **Operator choices are pinned, not hand-waved.** CloudNativePG, Strimzi, MinIO Operator, Keycloak Operator, KEDA 2.13+ are named with version policies and external-alternative escape hatches per dependency (KubernetesOpenShift §4; DeploymentModel §3). Postgres failover mechanism (CNPG replica promotion, ~30–60 s) is explicit (DeploymentModel §9) and consistent with the ≤30 min RTO.
2. **Expand–contract is enforced, not aspirational.** The migrations job refuses to run a `contract` migration while the previous app version is deployed (version handshake table, DeploymentModel §8); rollback safety is restated identically in KubernetesOpenShift §13 and ReleaseManagement §7 with named enforcing gates (G4/CC-4).
3. **Air-gap story is unusually complete**: digest-pinned image manifests, `oc-mirror` incl. operator catalogs, offline model bundles with per-index embedding version tracking, signed offline bundle whose air-gapped smoke-install is a release gate (DeploymentModel §7; KubernetesOpenShift §14; DockerCompose §11; ReleaseManagement §5.2/RG4). No license/entitlement server exists anywhere — verified.
4. **Private CA / egress proxy is covered on both stacks**, including OpenShift cluster-wide trust-bundle injection, cluster `Proxy` object sourcing, `NO_PROXY` internal-name discipline, and a hard "no insecure-skip-verify" rule (KubernetesOpenShift §5.2; DockerCompose §14; DeploymentModel §12).
5. **RTO semantics are disciplined**: failover RTO (≤30 min) vs full-rebuild RTO (≤4 h) are consistently distinguished across DeploymentModel §9, KubernetesOpenShift §9, and OperationsGuide §6.3, with *both* quarterly drills mandated and release-gated (RG4).
6. **Kustomize-over-Helm decision is well-argued for this market** (auditability, air-gap, patch-over-fork; KubernetesOpenShift §1), and `restricted-v2`/arbitrary-UID/FIPS/SELinux conformance is specified up front (KubernetesOpenShift §6; DockerCompose §15).
7. **Governance actually enforces this area**: RG4 requires the upgrade path executed "verbatim" from OperationsGuide §7 with rollback rehearsal, drill records, and bundle smoke-install per train (ReleaseManagement §3–§4); OperationsGuide §15 is a real operational-readiness audit; "a backup that has not been restore-tested is treated as nonexistent" (OperationsGuide §6.3).

## 4. Weaknesses

1. **Migration-gate mechanism unnamed outside GitOps.** "Job success gates rolling update" (KubernetesOpenShift §13) is only mechanized for Argo CD (pre-sync hook). Kustomize has no hooks; plain `kubectl apply` of the built output applies Job and Deployments simultaneously — new app pods can start against the un-migrated schema. No app-side schema-version startup gate is specified either. The zero-downtime acceptance criterion (DeploymentModel §13) is not credible without this. (DEP-02)
2. **Backup failure-domain requirement missing.** OperationsGuide §2.3/§6.1 require the backup target only to be "reachable"; nothing requires it to be outside the primary failure domain. A single-DC install with colocated backups has unbounded RPO on site loss — silently voiding NFR-021/NFR-022. (DEP-03)
3. **2-DC reality unaddressed.** Topology C is 3-AZ (DeploymentModel §4); MirrorMaker2 appears once in an RPO cell (DeploymentModel §9) with no design, runbook, or quorum discussion. Many target enterprises have exactly two DCs. (DEP-05)
4. **Multi-TB restore vs RTO never analyzed.** The ≤4 h rebuild bound (DeploymentModel §9) is asserted independent of database size; at the sizing table's own 1–4 TB NVMe tier (DeploymentModel §5), base-backup restore + WAL replay plausibly exceeds 4 h without parallel restore (pgBackRest) or snapshot-based recovery — neither is specified for K8s (pgBackRest is named only for Compose, OperationsGuide §6.1). (DEP-06)
5. **Grafana phone-home defaults contradict the no-egress claim.** DeploymentModel §10 asserts "no telemetry phone-home", but Grafana OSS defaults to usage analytics and update checks; no doc pins `GF_ANALYTICS_REPORTING_ENABLED=false` / update-check disabling (DockerCompose §2/§5; KubernetesOpenShift §10). Blocked by NetworkPolicy in K8s, unblocked in Compose/proxy environments. (DEP-07)
6. **Redis HA is under-specified relative to its peers**: "StatefulSet (or enterprise-managed Redis) … replication + sentinel via operator optional, manual" (KubernetesOpenShift §3) vs "primary + replica, Sentinel" (DeploymentModel §3) — no operator or manifest shape pinned, and the Redis TLS port story is inconsistent (6380/TLS in DeploymentModel §10 vs 6379 in KubernetesOpenShift §6 row 6 vs plaintext Compose). Fail-open semantics limit the blast radius, but an implementer must guess. (DEP-09)
7. **PgBouncer/connection pooling is mentioned but not deployed anywhere**: DeploymentModel §6 says "PgBouncer/connection pool in front"; OperationsGuide §9 T6 even constrains its mode (transaction pooling), yet no workload-catalog entry or CNPG `Pooler` CR exists in KubernetesOpenShift §3. (DEP-11)
8. **CI at scale unplanned**: full-monorepo compile per PR with no affected-module selection, no remote build cache decision, and no named dependency mirror for the air-gapped CI profile (QualityGatePolicy G1; TestingStrategy §14/§16; RepositoryStructure §2). Fine at Phase 0–2 team size; degrades linearly with module and test growth. (DEP-12)
9. **Partition-increase runbook is unsafe as written** — see Contradiction C3. (DEP-04)

## 5. Contradictions

| # | Contradiction | Side A | Side B |
|---|---|---|---|
| C1 | **Upgrade rollout order** — app-first vs workers-first | DeploymentModel §8 "Order of operations: migrations job → `eip-app` → workers → frontend"; same order in KubernetesOpenShift §13 step 3–5 | OperationsGuide §7 step 4: "update `eip-workers` **first** (they tolerate both schema versions), then remaining `eip-app` replicas, then frontend". ReleaseManagement RG4 requires OperationsGuide §7 be followed *verbatim*, so the release gate rehearses a different order than the manifests implement. |
| C2 | **`eip-app` autoscaling signal** | DeploymentModel §6: HPA on RPS via custom metric (Prometheus adapter), fallback CPU 70% | KubernetesOpenShift §3 and §11 (the self-declared authoritative spec): HPA on CPU 70% only |
| C3 | **Doc vs Kafka reality: partition increase "preserves ordering"** | OperationsGuide §5: "raise partitions on the hot `eip.*` topic (ordering per `tenantId:entityId` key is preserved…)" | Kafka reality: increasing partitions changes key→partition mapping; a lagging consumer can process a key's new-partition messages before its old-partition backlog — per-key ordering (the guarantee EventModel §7 and DeploymentModel §6 rely on for state machines) is violated during the transition. Dedup on `eventId` absorbs duplicates, not reordering. |
| C4 | **Migrations job contents** | DeploymentModel §1: migrations job = "Flyway migrations against PostgreSQL 16" | DockerCompose §2/§4 and KubernetesOpenShift §3: the same job also performs Kafka topic provisioning. The normative Compose excerpt (DockerCompose §3) `depends_on` kafka but passes **no** Kafka bootstrap env to `eip-migrate`. |
| C5 | **Namespace naming and Keycloak placement** | DeploymentModel §3 diagram: namespaces `eip` / `eip-data` with Keycloak in `eip-data` | KubernetesOpenShift §2: `eip-system` / `eip-data` / `eip-observability` with Keycloak in `eip-system` |
| C6 | **Redis port/TLS** | DeploymentModel §10 flow table: Redis 6380/TLS ("TLS everywhere", §12) | KubernetesOpenShift §6 row 6: Redis 6379, no TLS annotation (Kafka row explicitly says TLS); no Redis TLS termination mechanism specified anywhere |

## 6. Missing decisions

An implementer will be forced to decide ad hoc:

1. **How the migration Job gates rollout on non-Argo installs** (plain `kubectl apply`, Flux): apply-and-wait script? app-side startup refusal when `flyway_schema_history` < required version? (DEP-02)
2. **Backup target placement policy** — which failure domain, replication of the backup store itself, and who verifies it. (DEP-03)
3. **The supported 2-site DR pattern** — stretch vs active-passive, MM2 topology, CNPG standby, MinIO site replication, DNS/LB failover, quorum/witness. (DEP-05)
4. **Safe partition-increase procedure** — drain-to-zero-lag prerequisite, or explicit acceptance of an ordering-violation window. (DEP-04)
5. **Redis HA mechanism** — which operator/manifests provide Sentinel; whether Redis TLS exists in-cluster and via what. (DEP-09)
6. **PgBouncer / CNPG Pooler**: deployed or not, and where in the manifest tree. (DEP-11)
7. **Egress-policy generation**: what component renders NetworkPolicies from connector configuration, when it applies them, and with what privileges; FQDN enforcement layer. (DEP-10)
8. **Air-gapped CI dependency source**: the mirror (Artifactory/Nexus) Gradle/pnpm resolve against, and how `verification-metadata.xml` interacts with it. (DEP-12)
9. **Operator installation on non-OLM vanilla K8s in air gap**: which install manifests ship in the release archive. (DEP-13)
10. **Grafana hardening block** for the no-phone-home claim. (DEP-07)

## 7. Risks

### Scalability
- **Postgres single-writer ceiling** is the one real 10× wall (repos/issues/tenants scenarios). The path (vertical → read replicas → Qdrant offload → module extraction per ArchitectureOverview D8) exists and is not foreclosed, but no ceiling or trigger is documented (DEP-17).
- **12-partition default** caps worker parallelism at 8–12; repartitioning is the growth lever and its procedure is currently unsafe (DEP-04).

### Operational
- Two contradictory upgrade orders (C1) *will* eventually be executed by an operator under pressure; the release gate rehearses one, the manifests encode the other (DEP-01).
- Migration/app race on non-GitOps clusters produces stalled rollouts or schema errors at the worst time — during upgrades (DEP-02).
- Multi-TB restore drills timed only quarterly may discover the 4 h bound is unachievable at real data volume (DEP-06).

### Data consistency
- Partition-increase reordering can corrupt per-key state machines downstream (C3/DEP-04).
- Colocated backups turn a site loss into unbounded data loss despite green NFR-021 dashboards (DEP-03).

### Security
- Grafana default egress contradicts the documented zero-phone-home posture — in proxy environments it will actually leave (DEP-07). Keycloak 24, Prometheus, OTel Collector, and vLLM/Ollama server images have no phone-home defaults; the claim is otherwise verified.

### Tenant isolation
- No per-tenant namespaces is correct and documented (KubernetesOpenShift §2); isolation risk lives in the data layer (RLS), out of this review's scope but with a good fail-closed runbook (OperationsGuide §9 T6).

### Implementation
- CI cost grows with the monorepo; without affected-module builds and a remote cache the ≤20-min PR budget (TestingStrategy §14) fails first, then gets waived — eroding the gate system (DEP-12).

## 8. Required fixes

| ID | Severity | Target document(s) | Description | Status |
|---|---|---|---|---|
| DEP-01 | MANDATORY | `docs/operations/OperationsGuide.md` §7 | Reconcile rollout order with DeploymentModel §8 / KubernetesOpenShift §13: change §7 step 4 to the canonical order "migrations → `eip-app` → workers (ingestion, analytics, ai, reports) → frontend", or record an ADR making workers-first canonical and change the other two docs — one order, everywhere. | APPLIED (2026-07-06) |
| DEP-02 | MANDATORY | `docs/infrastructure/KubernetesOpenShift.md` §13 (+ §12.1) | Specify the migration-gate mechanism for non-GitOps installs: (a) documented apply sequence `kubectl apply` Job → `kubectl wait --for=condition=complete` → apply the rest (shipped as `scripts/deploy.sh`), **and** (b) an app/worker startup gate that fails readiness when `flyway_schema_history` lacks the release's required version (tying into the DeploymentModel §8 version-handshake table). State both normatively. | APPLIED (2026-07-06) |
| DEP-03 | MANDATORY | `docs/operations/OperationsGuide.md` §2.3 + §6.1; `docs/architecture/DeploymentModel.md` §9 | Add the requirement: backup targets (Postgres base+WAL, MinIO mirror, config/key escrow) MUST reside in a separate failure domain from the primary deployment (second DC, separate building, or off-site object store); the quarterly restore drill must restore from that off-site copy. State explicitly that colocated backups void NFR-021/022 on site loss. | APPLIED (2026-07-06) |
| DEP-04 | RECOMMENDED | `docs/operations/OperationsGuide.md` §5 + §10; `docs/architecture/DeploymentModel.md` §6 | Replace the claim that raising partitions preserves per-key ordering with a safe procedure: pause producers or drain consumer lag to ~0 on the topic before increasing partitions (maintenance window), then resume; document that ordering is violated for in-flight backlogs otherwise. Add 10× partition-sizing guidance (partitions per topic vs target events/s). | SCHEDULED (pre-Sprint-3) |
| DEP-05 | RECOMMENDED | `docs/architecture/DeploymentModel.md` new §4.1 (or §9); `docs/operations/OperationsGuide.md` §6 | Add a supported two-datacenter topology: active-passive second site using CNPG replica cluster (or WAL-shipping standby), MinIO site replication, MirrorMaker2 (or accept Kafka rebuild-on-failover, which §9 already permits), documented site-failover runbook with RTO/RPO for site loss, and an explicit statement that 2-site stretch clusters are NOT supported (quorum). | SCHEDULED (pre-Sprint-3) |
| DEP-06 | RECOMMENDED | `docs/architecture/DeploymentModel.md` §9; `docs/operations/OperationsGuide.md` §6.3 | Add restore-time-at-scale analysis: state the maximum Postgres database size for which the ≤4 h rebuild RTO is committed on reference hardware; specify pgBackRest (or CNPG barman) parallel restore for K8s (currently named only for Compose) and volume-snapshot-based recovery as the multi-TB path; require the quarterly drill to run at production-representative data volume. | SCHEDULED (pre-Sprint-3) |
| DEP-07 | RECOMMENDED | `docs/infrastructure/DockerCompose.md` §2/§5; `docs/infrastructure/KubernetesOpenShift.md` §10 | Pin Grafana no-egress config in both stacks: `GF_ANALYTICS_REPORTING_ENABLED=false`, `GF_ANALYTICS_CHECK_FOR_UPDATES=false`, `GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES=false`, news feed disabled — so the DeploymentModel §10 "no phone-home" claim is true by configuration, not by firewall accident. | SCHEDULED (pre-Sprint-3) |
| DEP-08 | RECOMMENDED | `docs/architecture/DeploymentModel.md` §6 | Align `eip-app` autoscaling with KubernetesOpenShift §11: either change §6 to "HPA on CPU 70% (custom RPS metric optional, Phase 5+)" or add the Prometheus-adapter RPS trigger to KubernetesOpenShift §11 — one signal of record. | SCHEDULED (pre-Sprint-3) |
| DEP-09 | RECOMMENDED | `docs/infrastructure/KubernetesOpenShift.md` §3 + §6; `docs/architecture/DeploymentModel.md` §10 | Pin the Redis HA mechanism (e.g., 3-node Sentinel via plain StatefulSet manifests in `/base`, or declare single-replica-acceptable given fail-open semantics) and resolve the TLS/port mismatch (6380/TLS vs 6379/plaintext): state whether in-cluster Redis TLS is required and via what (Redis 7 native TLS with cert-manager certs, or accepted-plaintext-with-NetworkPolicy). | SCHEDULED (pre-Sprint-3) |
| DEP-10 | RECOMMENDED | `docs/architecture/DeploymentModel.md` §10; `docs/infrastructure/KubernetesOpenShift.md` §6 | Name the egress-policy generation mechanism: which component renders connector-endpoint egress rules, whether application or install-time, and the enforcement layer for FQDN egress (OpenShift EgressFirewall / Cilium FQDN policy) with plain-NetworkPolicy CIDR fallback. | SCHEDULED (pre-Sprint-3) |
| DEP-11 | RECOMMENDED | `docs/infrastructure/KubernetesOpenShift.md` §3 + §4 | Decide connection pooling: add CNPG `Pooler` (PgBouncer, transaction mode — consistent with `SET LOCAL app.tenant_id`) to the workload catalog for k8s-prod, or state that Hikari-only is the supported posture and delete the PgBouncer references in DeploymentModel §6 / OperationsGuide §9 T6. | SCHEDULED (pre-Sprint-3) |
| DEP-12 | RECOMMENDED | `docs/testing/TestingStrategy.md` §14; `engineering-operating-system/DevelopmentLifecycle.md` or `QualityGatePolicy.md` G1 | Specify CI-at-scale mechanics: affected-module detection for Gradle (test only changed modules + dependents on PR; full build nightly), Gradle remote build cache and pnpm store cache decision, and the named dependency mirror (Artifactory/Nexus) air-gapped CI runners resolve against, wired to `gradle/verification-metadata.xml`. | SCHEDULED (pre-Sprint-3) |
| DEP-13 | RECOMMENDED | `docs/infrastructure/KubernetesOpenShift.md` §14 | Add to the release bundle contents: pinned operator installation manifests (CNPG, Strimzi, MinIO, Keycloak, KEDA) for non-OLM vanilla-K8s air-gapped clusters, mirroring the `oc-mirror` catalog path that exists for OpenShift. | SCHEDULED (pre-Sprint-3) |
| DEP-14 | RECOMMENDED | `docs/architecture/DeploymentModel.md` §1; `docs/infrastructure/DockerCompose.md` §3 | Make the migrations job description consistent: DeploymentModel §1 must state "Flyway migrations + idempotent `eip.*` topic provisioning"; add `EIP_KAFKA_BOOTSTRAP_SERVERS` to the normative `eip-migrate` Compose excerpt. | SCHEDULED (pre-Sprint-3) |
| DEP-15 | RECOMMENDED | `docs/architecture/DeploymentModel.md` §3 (diagram + text) | Align namespace names and Keycloak placement with KubernetesOpenShift §2 (`eip-system`/`eip-data`/`eip-observability`; Keycloak in `eip-system`). | SCHEDULED (pre-Sprint-3) |
| DEP-16 | RECOMMENDED | `docs/infrastructure/KubernetesOpenShift.md` §7 | Document MinIO capacity growth: expansion happens by adding server pools (not single nodes); state the recommended pool shape and that erasure-set parameters are fixed per pool at creation. | SCHEDULED (pre-Sprint-3) |
| DEP-17 | ACCEPTED_RISK | `docs/architecture/DeploymentModel.md` §5 | Document the deployment ceiling: the specified topologies are sized for the D3 envelope (~5,000 engineers, ≥10k repos, ≥1M WorkItems, 50 tenants). Beyond ~3–5× on the data axes, PostgreSQL vertical scaling is the binding constraint; revisit trigger: sustained p95 dashboard latency breach or DB > 2 TB — response levers: read replicas, Qdrant offload, `eip-ingestion`/`eip-ai` extraction per ArchitectureOverview D8. Consciously accepted for MVP. | ACCEPTED (MVP) |
| DEP-18 | ACCEPTED_RISK | `docs/operations/OperationsGuide.md` §6.2 | State explicitly that post-restore connector re-sync backfill (step 6) is outside the RTO clock and, at 10× data volume (100k repos / 10M issues), may take days constrained by source-tool rate limits; the platform is "restored" when serving from restored state, "current" only after backfill. | ACCEPTED (MVP) |

## 9. Area verdict & conditions

**Verdict: READY WITH CONDITIONS.** Mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items.

The deployment architecture is one of the more mature areas of this documentation set: operators are pinned, the air-gap and private-CA/proxy paths are concrete and release-gated, expand–contract is mechanically enforced, and failover-vs-rebuild RTO semantics are disciplined and drilled. At the committed envelope it holds. At 10× it degrades where every shared-Postgres monolith degrades, with credible, non-foreclosed scale-out levers — provided the documented ceiling and the partition/restore procedures are made honest.

Readiness is gated on three MANDATORY conditions, all resolvable in the source documents before implementation starts:

- **DEP-01** — one canonical upgrade rollout order across OperationsGuide §7, DeploymentModel §8, KubernetesOpenShift §13.
- **DEP-02** — a named, normative migration-gate mechanism for non-GitOps Kubernetes installs (apply-and-wait + app-side schema-version readiness gate).
- **DEP-03** — backup targets required in a separate failure domain, drilled from the off-site copy.

RECOMMENDED items (DEP-04…DEP-16) should land by Sprint 3 / next design iteration — DEP-04 (partition-increase safety) and DEP-05 (2-DC topology) first, as both will be demanded by the first enterprise customer conversation. DEP-17/DEP-18 are accepted risks once written down.

## 10. Inviolable principles

1. **Expand–contract, always.** Every release's schema and event changes are backward-compatible with N-1; image rollback one version never requires a database restore; `flyway_schema_history` is never hand-edited.
2. **Migrations complete before new code serves.** No app or worker instance of version N processes traffic against a schema older than N's expand set; a failed migration halts the rollout.
3. **Zero unauthorized egress.** The only outbound flows are tenant-configured connector endpoints, allow-listed MCP servers, and the optional LLM endpoint — including third-party bundled components' defaults (Grafana etc.). No phone-home, no license checks, no runtime downloads, ever.
4. **Air-gap parity is total.** Every feature, every image (by digest), every model, and every operator needed for install/upgrade ships in the signed offline bundle; the bundle is smoke-installed on a no-network host before release.
5. **A backup outside a tested restore is nonexistent**, and a backup inside the primary failure domain is not a backup.
6. **Per-key ordering (`tenantId:entityId`) is never knowingly violated** by an operational action; worker replicas never exceed partition counts.
7. **App and workers are stateless**: all state in PostgreSQL/Redis/Kafka/MinIO; scale-out is replica count, nothing else.
8. **Kustomize plain-YAML output is the single manifest source of truth**; site changes are overlay patches, never base or application-logic edits; any Helm chart is generated, never divergent.
9. **Image conformance never regresses**: non-root, arbitrary-UID, `restricted-v2`, read-only rootfs, capabilities dropped, digest-referenced in mirrored registries.
10. **RTO/RPO are drilled, gated facts**: quarterly failover and restore drills, RG4 upgrade + rollback rehearsal per train — a release does not ship on assertions.
