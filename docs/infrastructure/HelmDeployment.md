# Helm Deployment Specification

This document specifies the **eip** Helm chart (`/infra/helm/eip`), a minimal, v0.1-scoped
packaging artifact for **eip-app-only pilot/evaluation clusters** (M5 Wave S2, DEBT-002/DEBT-005
packaging + supply-chain hardening). For demo/eval/small single-host installs use Docker Compose
(`./DockerCompose.md`); for local development use `./LocalInstall.md`. For the eventual GA
production topology (HA, autoscaling, operator-managed data services, NetworkPolicies) see
`./KubernetesOpenShift.md` — that document's Kustomize base remains the **source of truth** the
platform is built toward; this chart is a smaller, earlier, secondary artifact for organizations
that want a one-command Helm install ahead of the full Kustomize topology landing, and it must
never diverge from `./KubernetesOpenShift.md`'s configuration contract (env var names, health
endpoints) where both exist.

## 1. Scope

The chart deploys **eip-app only** by default — one Deployment, one Service, a ConfigMap for
non-secret runtime config, and a Secret for the three secret-bearing values. The frontend is
gated behind `frontend.enabled` (default `false`) and Ingress behind `ingress.enabled` (default
`false`); both templates exist and render correctly but are inert until an operator has a real
frontend image / ingress controller + certificate story.

**Air-gap principle (unchanged from Compose/Kustomize):** the chart pulls nothing beyond the
`eip-app` (and optional frontend) image. PostgreSQL 16+pgvector, Kafka (KRaft), Redis, an OIDC
identity provider, and MinIO/S3 are **not** subcharts or bundled dependencies — every enterprise
installing this chart points `backend.env` at its own managed or on-prem instance of each. This
mirrors `./DockerCompose.md` §1.1's `.env` contract and `./KubernetesOpenShift.md` §4's
external-dependency posture, just without the operator-managed CRs Kustomize's `k8s-prod` overlay
provisions.

## 2. Install / Upgrade

```bash
# Render before applying, to review exactly what will be created (no cluster required):
helm template eip infra/helm/eip -f my-values.yaml

# Lint (structural + best-practice checks):
helm lint infra/helm/eip

# Install (namespace must exist or add --create-namespace):
helm install eip infra/helm/eip \
  --namespace eip-system --create-namespace \
  -f my-values.yaml

# Upgrade (after bumping image.tag / backend.env / secrets provisioning):
helm upgrade eip infra/helm/eip -n eip-system -f my-values.yaml

# Rollback to the previous release:
helm rollback eip -n eip-system

# Uninstall:
helm uninstall eip -n eip-system
```

`my-values.yaml` is a values override file kept out of version control if it carries anything
beyond `CHANGE_ME` (see §4). Never pass real secrets via `--set` on the command line — they land
in shell history and `helm get values` output; prefer `--set-file`, a values file readable only by
the operator, or (preferred) `secrets.existingSecret` (§4).

## 3. Values Reference

Full detail lives in `/infra/helm/eip/values.yaml`'s comments; this is the summary of the keys an
operator actually edits.

| Key | Default | Notes |
|---|---|---|
| `image.repository` / `image.tag` | `eip/eip-app` / `0.1.0` | No published image exists yet in this repo (no `Dockerfile` — a separate packaging task); point this at your build or mirror. Never a floating tag (`VersioningStrategy.md`: `latest` is prohibited everywhere). |
| `imagePullSecrets` | `[]` | Private/mirrored registry credentials for air-gap installs. |
| `replicaCount` | `1` | `eip-app` is stateless; scale as needed (no leader-election/singleton state). |
| `containerPort` | `8080` | Single port for both `/api/v1` and `/actuator/*` — `application.yaml` sets no `management.server.port`, so the two-port split `./KubernetesOpenShift.md` §8 describes is not implemented yet. This chart tracks the real application. |
| `service.type` / `service.port` | `ClusterIP` / `8080` | |
| `resources` | `500m`/`2` CPU, `1Gi` mem (req=limit) | Memory request==limit avoids JVM heap/cgroup-limit mismatch (`MaxRAMPercentage`), matching `./KubernetesOpenShift.md` §8's convention. |
| `ingress.enabled` | `false` | Optional; when enabled, routes to the backend Service (or the frontend Service if `frontend.enabled`). |
| `backend.springProfilesActive` | `prod` | Fixed — this chart has no seeded/demo mode. |
| `backend.env.*` | see below | Non-secret runtime config; variable **names** mirror `config/environments/prod.env` exactly. |
| `secrets.*` | `CHANGE_ME` | Secret-bearing values — §4. |
| `frontend.enabled` | `false` | No frontend image is published from this repo yet; flip once one exists. |

### 3.1 `backend.env` — mirrors `config/environments/prod.env`

| Variable | Consumed by (`application.yaml`) | Meaning |
|---|---|---|
| `EIP_DB_URL` | `spring.datasource.url` / `spring.flyway.url` | PostgreSQL 16+pgvector JDBC URL. |
| `EIP_APP_DB_USER` | `spring.datasource.username` | The RLS-enforced `eip_app` (`NOBYPASSRLS`) role (`DatabasePlan` §2/§5). |
| `EIP_MIGRATOR_DB_USER` | `spring.flyway.user` | The privileged Flyway migrator role. |
| `EIP_KAFKA_BOOTSTRAP` | `spring.kafka.bootstrap-servers` | Kafka (KRaft) bootstrap servers for the `eip.*` topics (`EventModel`). |
| `EIP_SECURITY_MODE` | `eip.security.mode` | Fixed to `oidc` in this chart — header/demo tenant resolution must never serve cluster traffic (`ProductionTenantResolutionGuard`, DEBT-012). |
| `EIP_OIDC_ISSUER` | `spring.security.oauth2.resourceserver.jwt.issuer-uri` | The enterprise IdP realm issuer (or a bundled/rehearsal Keycloak). |
| `EIP_OTLP_TRACES_ENDPOINT` | `management.otlp.tracing.endpoint` | OTel Collector OTLP/HTTP receiver (`ObservabilityModel`). |

These are rendered into a ConfigMap (`<release>-backend-config`) and consumed via `envFrom` — safe
to `kubectl get configmap -o yaml` for support/audit, since no secret-bearing value is ever a
ConfigMap key.

## 4. Secret Provisioning (DEBT-005)

`EIP_APP_DB_PASSWORD`, `EIP_MIGRATOR_DB_PASSWORD`, and `EIP_SECRETS_MASTER_KEY` (the ADR-014
envelope-encryption KEK) are secret-bearing and follow the same `CHANGE_ME` convention as
`config/environments/{prod,preprod}.env`. **`ProductionSecretsGuard`**
(`com.eip.app.config`, DEBT-005) refuses `eip-app` boot outright — crash-looping the pod, by
design — if any of the three is blank, `CHANGE_ME`, or a known dev/demo fixture value (the same
guard `scripts/install/install.sh`'s local prod/preprod rehearsal is subject to). This chart never
silently deploys with a placeholder secret.

Two provisioning paths:

1. **Chart-managed Secret** (`values.yaml` `secrets.appDbPassword` / `secrets.migratorDbPassword`
   / `secrets.secretsMasterKey`) — simplest for a first pilot install:

   ```bash
   helm install eip infra/helm/eip -n eip-system \
     --set secrets.appDbPassword="$(openssl rand -base64 24)" \
     --set secrets.migratorDbPassword="$(openssl rand -base64 24)" \
     --set secrets.secretsMasterKey="$(openssl rand -base64 32)" \
     -f my-values.yaml
   ```

   The chart renders a single `Opaque` Secret (`<release>-backend-secrets`) with those three keys.

2. **Pre-provisioned Secret** (`secrets.existingSecret`) — preferred once an enterprise secret
   pipeline exists; the chart's own `secret.yaml` template does not render and
   `secrets.appDbPassword`/etc. are ignored:

   ```bash
   kubectl create secret generic eip-secrets -n eip-system \
     --from-literal=EIP_APP_DB_PASSWORD="$(vault kv get -field=app_db_password ...)" \
     --from-literal=EIP_MIGRATOR_DB_PASSWORD="$(vault kv get -field=migrator_db_password ...)" \
     --from-literal=EIP_SECRETS_MASTER_KEY="$(vault kv get -field=master_key ...)"

   helm install eip infra/helm/eip -n eip-system \
     --set secrets.existingSecret=eip-secrets \
     -f my-values.yaml
   ```

   An **External Secrets Operator** `ExternalSecret` targeting a Vault KV/AWS SM/Azure KV backend
   and syncing into a Secret named `eip-secrets` achieves the same result declaratively — this is
   the `k8s-prod` overlay's approach in `./KubernetesOpenShift.md` §5, and this chart's
   `secrets.existingSecret` knob is the Helm-native equivalent.

## 5. Air-Gap Procedure

The chart itself has no chart-repository dependency (no `Chart.yaml` `dependencies:` block) and no
external `helm repo add` is required — copy `/infra/helm/eip` across the air gap alongside the
image bundle below.

**Image mirror list** (mirror every image the *cluster* needs — the chart deploys only `eip-app`,
but the referenced infra it depends on for a full pilot is documented here for one-pass mirroring;
these are the images `infra/docker-compose/docker-compose.yml` pins for local/small installs — a
cluster install substitutes managed/operator-provisioned equivalents where available, but these
exact images+tags are the known-good reference versions):

| Image | Tag | Role |
|---|---|---|
| `eip/eip-app` | *(operator build; not yet published — see §3)* | The chart's own workload |
| `pgvector/pgvector` | `pg16` | PostgreSQL 16 + pgvector |
| `redis` | `7-alpine` | Cache / Redisson locks |
| `apache/kafka` | `3.7.0` | Kafka (KRaft) |
| `minio/minio` | `RELEASE.2024-06-13T22-53-53Z` | Object storage |
| `minio/mc` | `RELEASE.2024-08-17T11-33-50Z` | One-shot bucket provisioning |
| `quay.io/keycloak/keycloak` | `24.0` | OIDC IdP (optional — enterprise IdP is equally valid) |
| `otel/opentelemetry-collector-contrib` | `0.102.0` | OTLP intake |
| `prom/prometheus` | `v2.53.0` | Metrics TSDB |
| `grafana/grafana` | `11.1.0` | Dashboards |

Mirror procedure (same tool as `./KubernetesOpenShift.md` §14):

```bash
for img in \
  "pgvector/pgvector:pg16" \
  "redis:7-alpine" \
  "apache/kafka:3.7.0" \
  "minio/minio:RELEASE.2024-06-13T22-53-53Z" \
  "minio/mc:RELEASE.2024-08-17T11-33-50Z" \
  "quay.io/keycloak/keycloak:24.0" \
  "otel/opentelemetry-collector-contrib:0.102.0" \
  "prom/prometheus:v2.53.0" \
  "grafana/grafana:11.1.0"; do
  skopeo copy "docker://${img}" "docker://registry.example.internal/eip/${img##*/}"
done
# eip-app: skopeo copy "docker://eip/eip-app:${EIP_VERSION}" "docker://registry.example.internal/eip/eip-app:${EIP_VERSION}"
```

**Registry override** — point the chart at the mirrored registry:

```bash
helm install eip infra/helm/eip -n eip-system \
  --set image.repository=registry.example.internal/eip/eip-app \
  --set image.tag="$EIP_VERSION" \
  --set imagePullSecrets[0].name=eip-registry-credentials \
  -f my-values.yaml
```

`values.yaml` intentionally does not hardcode a registry host — every `image.repository` is a full
override point, so a single `--set`/values-file change is sufficient; no template edits.

## 6. Relationship to the Docker Compose Install

`./DockerCompose.md` and `./LocalInstall.md` target a single host (demo, evaluation, small
production up to ~500 engineers) via `docker compose`; this chart targets a Kubernetes cluster.
They share the same application configuration contract (`config/environments/*.env` variable
names, `ProductionSecretsGuard`/`ProductionTenantResolutionGuard` boot guards, expand-contract
schema migrations) so an operator who has rehearsed a `--env preprod` local install
(`scripts/install/install.sh`, `./LocalInstall.md`) recognizes every `backend.env`/`secrets.*` key
in this chart. Neither install path is a stepping stone to the other — choose Compose for a
single-host footprint, this chart (or, once it lands, the `./KubernetesOpenShift.md` Kustomize
base) for a cluster footprint.

## 7. Validation Performed

`helm lint` and `helm template` (all three toggle combinations — defaults, `frontend.enabled` +
`ingress.enabled`, and `secrets.existingSecret`) were run against every chart change in this repo
(via a local `alpine/helm:3` container image where no `helm` binary is installed) and must stay
clean before any chart edit merges.
