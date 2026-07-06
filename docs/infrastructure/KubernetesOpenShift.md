# Kubernetes / OpenShift Deployment Specification

This document is the authoritative specification for deploying the **Engineering Intelligence Platform (EIP)** on Kubernetes and OpenShift. It is the target for production, HA, and enterprise installs (Phase 5 GA per the roadmap in `../vision/Vision.md`); the manifests it describes live under `/infra/kubernetes` and do not exist yet — build them to match this spec. For demo/eval/small installs use Docker Compose (`./DockerCompose.md`); for the application architecture and event model see `../architecture/ArchitectureOverview.md`.

Supported platforms: Kubernetes 1.28+ (any conformant distribution) and OpenShift 4.14+.

## 1. Manifest Strategy: Kustomize Base + Overlays

```
/infra/kubernetes
  /base                      # complete, environment-agnostic manifests
    /app                     # eip-app, eip-workers, frontend, jobs, cronjobs
    /config                  # ConfigMaps, Secret templates (values external)
    /policy                  # NetworkPolicies, PDBs, ServiceAccounts, RBAC
    /observability           # ServiceMonitors, OTel Collector
  /overlays
    /k8s-small               # 1 replica tiers, no zone spread, small PVCs
    /k8s-prod                # HA replica counts, PDBs, anti-affinity, KEDA
    /openshift               # Routes, SCC-conformant tweaks, internal-registry image refs
  /components                # optional: keda, external-secrets, ai-local, qdrant
```

**Why Kustomize (not Helm) as the primary delivery:**

- **On-prem auditability.** Enterprise security teams reviewing an on-prem install must be able to read exactly what will be applied. Kustomize output is plain YAML derivable with `kubectl kustomize` — no template language, no logic hidden in `{{ }}` conditionals, no values-file indirection several layers deep. `kustomize build overlays/k8s-prod > applied.yaml` produces a complete, diffable, sign-off-able artifact.
- **Air-gap friendliness.** No chart repository, no Helm release state in-cluster (a Secret-stored release history is one more thing to audit and back up); GitOps tools (Argo CD, Flux, and `oc apply`) consume Kustomize natively.
- **Patch-over-fork.** Enterprises inevitably need site-specific changes (annotations, sidecars, storage classes). Kustomize strategic-merge/JSON6902 patches keep those as small reviewable overlays on an unmodified base, surviving upgrades cleanly.
- A **Helm chart is a later, secondary option** (post-GA) generated from the same base for organizations whose platform tooling standardizes on Helm; Kustomize remains the source of truth and the chart must never diverge from it.

## 2. Namespace Layout

| Namespace | Contents | Rationale |
|---|---|---|
| `eip-system` | eip-app, eip-workers, frontend, migration Job, CronJobs, OTel Collector deployment-mode instance, Keycloak (when bundled) | Application trust zone; the only namespace exposed via Ingress/Route. |
| `eip-data` | Postgres (CloudNativePG cluster), Kafka (Strimzi), Redis, MinIO, Qdrant (optional) | Data trust zone; stricter NetworkPolicy, separate quota, storage-heavy. |
| `eip-observability` | Prometheus/Grafana (when not using a cluster-shared stack), Tempo/Loki (optional) | Isolates telemetry retention and access from app operators. |

Multi-tenancy of EIP itself is application-level (tenant_id + Postgres RLS); one EIP installation serves many tenants — do **not** deploy per-tenant namespaces.

## 3. Workload Catalog

| Component | Kind | Replicas (small / prod) | Scaling | Notes |
|---|---|---|---|---|
| eip-app | Deployment | 1 / 3 | HPA on CPU (prod: min 3, max 6, target 70%) | Stateless API; rolling update `maxUnavailable: 0, maxSurge: 1`. |
| eip-workers-ingestion | Deployment | 1 / 2 | **KEDA ScaledObject on Kafka consumer lag** (Section 11) | `EIP_WORKER_PROFILES=ingestion`; consumes `eip.raw.*`, domain normalizer groups. |
| eip-workers-analytics | Deployment | 1 / 2 | KEDA on lag of `eip.domain.*`, `eip.analytics.metrics` groups | `EIP_WORKER_PROFILES=analytics`. |
| eip-workers-ai | Deployment | 1 / 2 | KEDA on lag of `eip.ai.jobs` | `EIP_WORKER_PROFILES=ai`; long-poll LLM calls, generous termination grace (300 s). |
| eip-workers-reports | Deployment | 1 / 1 | KEDA on lag of `eip.reports.jobs` | `EIP_WORKER_PROFILES=reports`. |
| frontend | Deployment | 1 / 2 | none (static + proxy, negligible load) | nginx serving SPA; `/api` upstream is the `eip-app` Service. |
| eip-migrate | Job | per release | n/a | Flyway + topic provisioning; gates rollout (Section 13). `backoffLimit: 0`, new Job name per version. |
| eip-report-schedules | CronJob | n/a | n/a | Enqueues scheduled report jobs onto `eip.reports.jobs` (schedules themselves live in DB; this is the tick). Every 5 min, `concurrencyPolicy: Forbid`. |
| eip-rag-reindex | CronJob | n/a | n/a | Scheduled RAG re-index sweep (incremental re-embedding of changed documents). Nightly, `concurrencyPolicy: Forbid`. |
| keycloak (bundled option) | via Keycloak Operator | 1 / 2 | operator-managed | Skipped entirely when an enterprise IdP (AD FS/Azure AD/Okta) is configured. |
| postgres | CloudNativePG `Cluster` | 1 / 3 (1 primary + 2 replicas) | operator-managed | pgvector-enabled image (Section 4). |
| kafka | Strimzi `Kafka` | 1 / 3 brokers, KRaft | operator-managed | RF=3 in prod for all `eip.*` topics. |
| redis | StatefulSet (or enterprise-managed Redis) | 1 / 3 (replication + sentinel via operator optional) | manual | Locks/cache; data loss tolerable, availability matters. |
| minio | MinIO Operator `Tenant` | 1 / 4 (erasure-coded) | operator-managed | Or any external S3 endpoint. |
| otel-collector | Deployment (gateway mode) | 1 / 2 | none | Section 10 for the DaemonSet-vs-sidecar decision. |
| qdrant (optional) | StatefulSet | 0 / as needed | manual | Only when `EIP_VECTORSTORE_PROVIDER=qdrant`. |

## 4. Dependency Operators

Default posture: EIP does not embed data services in its own manifests; it depends on operators (installed cluster-side by the platform team) and declares the CRs in `eip-data`.

| Dependency | Operator | Version policy | External alternative |
|---|---|---|---|
| PostgreSQL 16 + pgvector | **CloudNativePG** | Track the operator's latest stable minor; Postgres major pinned to 16 until an EIP release certifies 17. Image must include pgvector (CNPG PostGIS/vector-enabled image or a custom build). | Any managed/enterprise Postgres 16 with `vector` extension installable and RLS available. Set `EIP_DB_URL` accordingly; CNPG CR omitted. |
| Kafka (KRaft) | **Strimzi** | Strimzi latest stable; Kafka broker version from the EIP compatibility matrix in release notes (3.7 at spec time). | Enterprise Kafka/Confluent/managed Kafka: provide bootstrap servers + TLS/SASL secret; Strimzi CRs omitted. Topic creation then runs solely via the migration Job. |
| Object storage | **MinIO Operator** | Latest stable operator; MinIO server pinned per release notes. | Any S3-compatible endpoint (enterprise object store, ODF/Noobaa on OpenShift). |
| Keycloak | **Keycloak Operator** | Keycloak 24.x line. | Enterprise IdP via OIDC — the common enterprise choice; the entire Keycloak footprint is optional. |
| Autoscaling | **KEDA** 2.13+ | Required for worker lag-based scaling; without KEDA, workers fall back to static replicas. | n/a |

Rule for enterprises with managed DB/Kafka/S3: every dependency is switchable to an external endpoint purely via configuration (Section 5) plus omission of the corresponding CR — no manifest surgery in `/base`.

## 5. Configuration and Secrets

- **ConfigMaps** (`eip-app-config`, `eip-workers-config`): all non-secret settings from the shared env contract (`EIP_KAFKA_BOOTSTRAP_SERVERS`, `EIP_S3_ENDPOINT`, `EIP_OIDC_ISSUER_URI`, `EIP_LLM_PROVIDER`, `EIP_LLM_BASE_URL`, `EIP_VECTORSTORE_PROVIDER`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `EIP_WORKER_PROFILES` per worker Deployment, Spring profile `k8s`). Checksum-annotation on pod templates forces rollout on config change.
- **Secrets**: `eip-db-credentials` (or CNPG-generated), `eip-oidc-client`, `eip-s3-credentials`, `eip-llm-api-key`, `eip-master-key`. Base manifests contain **structure only** (names/keys), never values.
- **External Secrets Operator / Vault**: the `k8s-prod` overlay includes `ExternalSecret` resources mapping each Secret to an enterprise backend (Vault KV, AWS SM, Azure KV). Direct Vault Agent injection is also supported for the master key. Plain `kubectl create secret` remains valid for `k8s-small`.
- **Secrets master key delivery**: the AES-256-GCM envelope-encryption master key (see `../architecture/ArchitectureOverview.md`) is mounted as a file — Secret `eip-master-key` → volume → `EIP_SECRETS_MASTER_KEY_FILE=/etc/eip/keys/master.key`. The inline env-var variant is prohibited in cluster deployments (visible via `kubectl describe`/crash dumps). With Vault, the pluggable KMS SPI can instead delegate envelope operations to Vault Transit (`EIP_SECRETS_KMS_PROVIDER=vault-transit`), removing the raw key from the cluster entirely. Key rotation follows the platform's secrets rotation procedure: new master key mounted alongside old (`master.key.next`), re-encryption job, swap, old key retired.

## 6. OpenShift Specifics

The `openshift` overlay assumes **no custom SCC**: everything runs under `restricted-v2`.

- **Image conformance (applies to all EIP images, enforced in CI):** non-root (`USER 1001` numeric), no privilege escalation (`allowPrivilegeEscalation: false`, all capabilities dropped, seccomp `RuntimeDefault`), and **arbitrary-UID support** — group-0 writable where writes are required, no UID assumptions in entrypoints; images run correctly under OpenShift's random project UID.
- **Routes vs Ingress:** base uses Ingress; the `openshift` overlay replaces it with a `Route` (edge TLS termination default, re-encrypt supported when the frontend serves TLS). Only `frontend` is externally exposed; `/api` and `/auth` are proxied through it exactly as in the compose topology.
- **Internal registry:** the overlay rewrites image references to `image-registry.openshift-image-registry.svc:5000/eip-system/<image>` (or the enterprise mirror, Section 14); ImageStreams are optional and not required by the manifests.
- **NetworkPolicy — default deny, explicit allows:** `/base/policy` ships a `default-deny-all` (ingress+egress) policy per namespace, plus:

| # | From | To | Port/Proto | Purpose |
|---|---|---|---|---|
| 1 | ingress controller / Router | frontend | 8080 (8443 re-encrypt) | External traffic |
| 2 | frontend | eip-app | 8080 | API proxy |
| 3 | frontend, eip-app | keycloak | 8080 | Auth proxy, token validation (bundled IdP) |
| 4 | eip-app, eip-workers-* | postgres (`eip-data`) | 5432 | Primary store |
| 5 | eip-app, eip-workers-* | kafka (`eip-data`) | 9092/9093 (TLS) | Event backbone |
| 6 | eip-app, eip-workers-* | redis (`eip-data`) | 6379 | Cache/locks |
| 7 | eip-app, eip-workers-* | minio (`eip-data`) | 9000 | Object storage |
| 8 | eip-workers-ai, eip-app | LLM endpoint (in-cluster ollama/vllm Service or egress CIDR) | 11434/8000 | LLM provider SPI |
| 9 | all app pods | otel-collector | 4317/4318 | Telemetry |
| 10 | prometheus (`eip-observability`) | app pods, exporters | 8081 / metrics ports | Scraping |
| 11 | eip-app, eip-workers-ingestion | connector targets (enterprise Jira/GitHub/SonarQube/… egress) | 443 | Connector syncs — the only broad egress, constrained by CIDR/FQDN policy per site |

## 7. Storage Classes and PVC Sizing

Storage class is a per-site parameter (`storageClassName` patched in overlays); requirements: RWO block storage, SSD-backed for Postgres and Kafka. No RWX volumes are required anywhere.

| PVC | Owner | k8s-small | k8s-prod (per replica) | Notes |
|---|---|---|---|---|
| postgres data | CNPG Cluster | 100 Gi | 500 Gi | Growth axis (~1–2 Gi / 100 engineers / month); CNPG handles expansion. |
| kafka log dirs | Strimzi | 50 Gi | 200 Gi × 3 | 7-day retention on `eip.*` topics. |
| minio | MinIO Tenant | 100 Gi | 250 Gi × 4 (EC:2) | Artifacts + ingested blobs. |
| redis AOF | StatefulSet | 5 Gi | 10 Gi | |
| prometheus | observability stack | 50 Gi | 100 Gi | 15-day retention default. |
| ollama models (optional) | StatefulSet | 50 Gi | 100 Gi | Model blobs. |
| qdrant (optional) | StatefulSet | 20 Gi | 100 Gi | |

## 8. Resource Requests / Limits and Probes

Requests are sized for steady state; limits cap noisy neighbors. JVM services derive heap from cgroup limits (`MaxRAMPercentage=75`), so memory request == limit (Guaranteed-adjacent) to avoid heap/limit mismatch.

| Component | CPU req / limit | Mem req=limit | Startup probe | Liveness | Readiness |
|---|---|---|---|---|---|
| eip-app | 500m / 2 | 3 Gi | `GET :8081/actuator/health/liveness`, 30 × 5 s (≤150 s JVM start) | same endpoint, 10 s period, 3 failures | `GET :8081/actuator/health/readiness` (checks db, kafka, redis, s3, oidc) |
| eip-workers-* | 500m / 2 | 3 Gi | as eip-app | as eip-app | readiness gates only consumer registration; workers receive no HTTP traffic |
| frontend | 50m / 250m | 128 Mi | `GET :8080/healthz`, 6 × 5 s | same | same |
| otel-collector | 200m / 1 | 512 Mi | — | `GET :13133/` | same |
| keycloak (bundled) | 500m / 1 | 1.5 Gi | operator defaults | `/health/live` | `/health/ready` |
| postgres / kafka / minio / redis | operator- or site-defined | — | — | operator-managed | operator-managed |

## 9. Topology and HA (`k8s-prod`)

- **Pod anti-affinity:** required-during-scheduling anti-affinity on hostname for eip-app replicas; preferred for each worker Deployment and frontend.
- **Zone spread:** `topologySpreadConstraints` on `topology.kubernetes.io/zone`, `maxSkew: 1`, `whenUnsatisfiable: ScheduleAnyway` for app/workers; CNPG, Strimzi, and MinIO configured for zone-spread replicas where the cluster spans zones (RF=3 aligns with 3 zones).
- **PodDisruptionBudgets:** eip-app `minAvailable: 2`; each worker profile `minAvailable: 1`; frontend `minAvailable: 1`; data-layer PDBs per operator defaults.
- Kafka consumers make worker disruption safe (at-least-once + idempotent consumers, dedup on `eventId`); the AI worker's 300 s `terminationGracePeriodSeconds` lets in-flight LLM jobs finish or checkpoint before eviction.

## 10. Observability Wiring

- **Scraping:** `ServiceMonitor`s for eip-app, each worker Deployment, frontend nginx exporter, and otel-collector; `PodMonitor` is used only for the Strimzi broker pods (per Strimzi docs). Compatible with prometheus-operator and OpenShift user-workload monitoring.
- **OTel Collector topology — gateway Deployment, not DaemonSet, not sidecar (decision):** EIP pods push OTLP directly to a 2-replica collector gateway Service. A DaemonSet buys per-node enrichment for fleets of heterogeneous workloads — EIP is ~8 pod types in one namespace, so per-node agents add cost without benefit; sidecars would multiply resource overhead per pod and complicate SCC review. The gateway centralizes tail-sampling, batching, and export (Prometheus remote-write/scrape, Tempo/Loki optional per the observability stack). Sites with an existing cluster-wide collector DaemonSet simply point `OTEL_EXPORTER_OTLP_ENDPOINT` at it and delete the bundled gateway — the app is agnostic.
- Dashboards from `/infra/grafana` are provisioned via a Grafana dashboard ConfigMap (label-sidecar convention) or imported into the enterprise Grafana.

## 11. Autoscaling Policy

| Workload | Mechanism | Trigger | Min / Max | Cooldown |
|---|---|---|---|---|
| eip-app | HPA | CPU 70% | 3 / 6 (prod) | HPA default stabilization (300 s down) |
| eip-workers-ingestion | KEDA ScaledObject | Kafka lag > 5000 across `eip.raw.*` + normalizer groups | 1 / 8 | 120 s |
| eip-workers-analytics | KEDA ScaledObject | Kafka lag > 5000 (`eip.domain.*`, `eip.analytics.metrics` groups) | 1 / 6 | 120 s |
| eip-workers-ai | KEDA ScaledObject | Kafka lag > 50 on `eip.ai.jobs` | 1 / 4 | 300 s (LLM jobs are long) |
| eip-workers-reports | KEDA ScaledObject | Kafka lag > 100 on `eip.reports.jobs` | 1 / 3 | 120 s |
| frontend, CronJobs, data layer | none | — | — | Static / operator-managed |

Max replicas per worker profile must not exceed the partition count of its busiest topic (12 partitions default) — extra replicas would idle.

## 12. Illustrative Manifest Excerpts (specification)

Normative excerpts — the future manifests must implement these shapes exactly (metadata/labels abbreviated).

### 12.1 `base/app/eip-app-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: eip-app
  namespace: eip-system
spec:
  replicas: 1                        # overlay-patched: k8s-prod sets 3 + HPA
  strategy:
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }   # zero-downtime
  selector: { matchLabels: { app.kubernetes.io/name: eip-app } }
  template:
    metadata:
      labels: { app.kubernetes.io/name: eip-app }
      annotations:
        eip.io/config-checksum: "{{kustomize-generated}}"  # roll pods on config change
    spec:
      serviceAccountName: eip-app
      securityContext:               # restricted-v2 conformant; no UID pinned (arbitrary UID)
        runAsNonRoot: true
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: eip-app
          image: eip/eip-app:1.0.0   # overlay-patched for mirrored registries
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: { drop: ["ALL"] }
            readOnlyRootFilesystem: true
          ports:
            - { name: http, containerPort: 8080 }
            - { name: management, containerPort: 8081 }
          envFrom:
            - configMapRef: { name: eip-app-config }
            - secretRef:    { name: eip-db-credentials }
            - secretRef:    { name: eip-oidc-client }
            - secretRef:    { name: eip-s3-credentials }
          env:
            - name: EIP_SECRETS_MASTER_KEY_FILE
              value: /etc/eip/keys/master.key
          volumeMounts:
            - { name: master-key, mountPath: /etc/eip/keys, readOnly: true }
            - { name: tmp, mountPath: /tmp }               # writable tmp for read-only rootfs
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            periodSeconds: 5
            failureThreshold: 30
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            periodSeconds: 10
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: management }
            periodSeconds: 10
          resources:
            requests: { cpu: 500m, memory: 3Gi }
            limits:   { cpu: "2",  memory: 3Gi }
      volumes:
        - name: master-key
          secret: { secretName: eip-master-key }
        - name: tmp
          emptyDir: {}
```

### 12.2 `overlays/k8s-prod/keda-ingestion-scaledobject.yaml`

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: eip-workers-ingestion
  namespace: eip-system
spec:
  scaleTargetRef: { name: eip-workers-ingestion }   # the Deployment
  minReplicaCount: 1
  maxReplicaCount: 8                                # ≤ partition count of busiest topic
  cooldownPeriod: 120
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: eip-kafka-bootstrap.eip-data.svc:9093
        consumerGroup: eip-ingestion                # group per worker profile
        topic: ""                                   # empty = all topics of the group
        lagThreshold: "5000"                        # events of lag per replica added
        activationLagThreshold: "100"
      authenticationRef: { name: eip-kafka-tls }    # TriggerAuthentication (Strimzi client certs)
```

## 13. Upgrade and Rollback

- **Versioning:** each EIP release tags images and ships a matching `/infra/kubernetes` tree; sites track it in Git (GitOps), and site patches live only in overlays, so `git merge` of a new release never conflicts with base.
- **Schema policy — expand-contract:** every release's migrations are *expand* (additive, backward-compatible with the previous app version); *contract* migrations (drops/renames) ship one release later and are flagged in release notes. Consequence: app version N and N-1 can run against the same schema during rollout, and image rollback one version back is always safe.
- **Rollout order per upgrade:** (1) apply new manifests — the migration Job (new name `eip-migrate-<version>`, `backoffLimit: 0`) is applied first / as a pre-sync hook in Argo CD; (2) Job success gates (3) rolling update of eip-app (`maxUnavailable: 0`), then (4) worker Deployments, then (5) frontend. CronJobs update with the manifest apply.
- **Rollback:** `kubectl rollout undo` (or Git revert in GitOps) on app/workers/frontend — safe within one version by the expand-contract guarantee. A failed migration Job halts everything before any app pod restarts; recovery is fix-forward or restore from the pre-upgrade database backup (CNPG point-in-time recovery).
- Data-layer upgrades (Postgres minor, Kafka broker, Keycloak) are operator-driven and decoupled from EIP releases; the compatibility matrix in release notes is authoritative.

## 14. Air-Gapped / Mirrored Registry Procedure

1. Release bundle includes `image-list.txt` (every image+digest: EIP images, data-service images, operator images per the versions policy) and signed checksums.
2. Mirror on a connected bastion: `skopeo copy --all docker://<src>@<digest> docker://registry.example.internal/eip/<name>` for each entry (script `scripts/mirror-images.sh` provided); on OpenShift, `oc-mirror` with the provided `ImageSetConfiguration` handles operator catalogs (CloudNativePG, Strimzi, MinIO, Keycloak, KEDA) plus the EIP list in one pass and generates `ImageDigestMirrorSet`/`CatalogSource` manifests.
3. Transfer the mirror tarball across the air gap; `oc-mirror --from` / `skopeo sync` into the disconnected registry.
4. Overlay patch: a single Kustomize image-transformer block in the site overlay rewrites all image references to the internal registry **by digest** (tags are not trusted across mirrors).
5. Local LLM: mirror the ollama/vllm image and transfer model weights as OCI artifacts or files onto the models PVC; `EIP_LLM_BASE_URL` points in-cluster. No EIP component requires internet egress (on-premise-first; only connector syncs reach enterprise-internal tools per NetworkPolicy row 11).

## 15. Production Readiness Checklist

- [ ] Overlay chosen and reviewed; `kustomize build` output diffed and signed off by security.
- [ ] All secrets sourced from External Secrets/Vault (no literal Secret values in Git); master key delivered as file mount or Vault Transit; rotation procedure tested.
- [ ] CNPG cluster: 3 instances, scheduled backups + WAL archiving to object storage, PITR restore drill executed successfully.
- [ ] Strimzi: 3 brokers, RF=3 and `min.insync.replicas=2` on all `eip.*` topics, TLS client auth for app/workers.
- [ ] MinIO (or external S3): erasure coding / redundancy confirmed; artifact bucket lifecycle policy set.
- [ ] OIDC against the real enterprise IdP verified (login, role/tenant claim mapping, token refresh); bundled Keycloak removed if unused.
- [ ] NetworkPolicies applied; default-deny verified by a probe pod (connections outside the allow table fail).
- [ ] On OpenShift: all pods admitted under `restricted-v2` (no custom SCC granted); Route TLS mode chosen; images served from internal/mirrored registry by digest.
- [ ] Probes green under load; startup probe headroom validated against slowest observed JVM start.
- [ ] HPA + KEDA verified: induced Kafka backlog scales workers out and back within cooldown; max replicas ≤ partition counts.
- [ ] PDBs + anti-affinity verified via a drain test (`kubectl drain` one node: no API downtime, workers rebalance).
- [ ] Observability: ServiceMonitors scraping, EIP Grafana dashboards live, alert rules (consumer lag, DLQ depth, migration Job failure, readiness flaps, CNPG replication lag) routed to the enterprise alerting channel.
- [ ] Upgrade rehearsal on staging: N-1 → N with rollout order of Section 13, then rollback of app images, both without data loss.
- [ ] Backup/restore drill for Postgres and MinIO completed end-to-end within the target RTO.
- [ ] Smoke test suite (as in `./DockerCompose.md` Section 13, adapted to cluster endpoints) passes, including a simulation-connector sync and one agent run with audit records present.
