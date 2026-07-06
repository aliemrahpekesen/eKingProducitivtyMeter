# Docker Compose Deployment Specification

This document is the authoritative specification for deploying the **Engineering Intelligence Platform (EIP)** with Docker Compose. It targets **demo, evaluation, and small production installs** (single host, one operator, up to roughly 500 tracked engineers and a handful of tenants). The compose files described here live under `/infra/docker-compose` and are Phase 0/Phase 5 deliverables; this document specifies their intended content precisely — build the files to match this spec.

Local development uses the same files but runs only the infrastructure services (see `./LocalDevelopment.md`). Installations needing HA, horizontal scaling, or enterprise operations must use Kubernetes/OpenShift (see `./KubernetesOpenShift.md`).

## 1. File Layout and Profiles

```
/infra/docker-compose
  compose.yaml            # single compose file, profile-gated
  .env.example            # documented contract; copy to .env per install
  keycloak/eip-realm.json # pre-seeded realm (dev/demo; production imports its own)
  nginx/frontend.conf     # SPA + reverse-proxy config for the frontend container
  nginx/tls/              # operator-provided cert/key (Section 8)
  otel/collector.yaml     # OTel Collector pipeline config
  prometheus/prometheus.yml
  postgres/00-extensions.sql  # CREATE EXTENSION IF NOT EXISTS vector;
  backup/                 # backup helper scripts (Section 7)
```

One `compose.yaml`, four profiles:

| Profile | Services enabled | When to use |
|---|---|---|
| `core` | eip-app, eip-workers, frontend, postgres, redis, kafka, minio, keycloak, eip-migrate, minio-init | Always. The minimum runnable platform. |
| `observability` | otel-collector, prometheus, grafana | Recommended for any install kept running more than a day. |
| `ai-local` | ollama (and optionally qdrant) | Air-gapped/local LLM serving. Omit when pointing `EIP_LLM_BASE_URL` at an external vLLM or other OpenAI-compatible endpoint. |
| `simulation` | eip-sim-seed one-shot job | Demo installs: loads a `/simulation` data pack through the simulation connector so dashboards are populated on first login. |

Invocation contract:

```bash
docker compose --profile core --profile observability up -d
```

### 1.1 `.env` contract

`.env.example` is the complete, documented list of variables (Section 5). Rules:

- Every secret-bearing variable in `.env.example` ships with the value `CHANGE_ME`; `eip-app` refuses to start (fails its config-validation step) if any `CHANGE_ME` value reaches it.
- `.env` is the only file an operator edits for a standard install. Compose file edits are reserved for port remapping and TLS wiring.
- All images are pinned by exact tag in `compose.yaml`; `EIP_VERSION` in `.env` selects the EIP application image tag only.

## 2. Service Catalog

All EIP images are non-root, read-only-rootfs-compatible, and expose healthchecks. Resource limits below are the compose `deploy.resources.limits` specification for a small production install (Section 10 for sizing).

| Service | Image | Purpose | Ports (host:container) | Volumes | Healthcheck | depends_on (condition) | Limits (CPU / mem) |
|---|---|---|---|---|---|---|---|
| eip-app | `eip/eip-app:${EIP_VERSION}` | API application (modular monolith composition root) | internal `8080` (API), `8081` (actuator); not host-published — fronted by `frontend` nginx | none (stateless) | `GET :8081/actuator/health/readiness` | eip-migrate (completed), postgres/redis/kafka/minio/keycloak (healthy) | 2.0 / 4g |
| eip-workers | `eip/eip-workers:${EIP_VERSION}` | Async worker runtime (ingestion, analytics, ai, reports consumer groups) | internal `8081` (actuator) | none (stateless) | `GET :8081/actuator/health/readiness` | eip-migrate (completed), postgres/redis/kafka/minio (healthy) | 2.0 / 4g per replica |
| frontend | `eip/frontend:${EIP_VERSION}` (nginx) | Serves the built SPA; reverse-proxies `/api` → eip-app, `/auth` → keycloak | `80:8080`, `443:8443` | `nginx/tls` (ro, when TLS enabled) | `GET :8080/healthz` | eip-app (started) | 0.5 / 256m |
| eip-migrate | `eip/eip-app:${EIP_VERSION}` (command `migrate`) | One-shot Flyway migration job; runs and exits before app/workers start | none | none | exit code (one-shot) | postgres (healthy) | 1.0 / 1g |
| postgres | `pgvector/pgvector:pg16` | Primary store + pgvector vector store | `5432` internal only | `eip_pg_data:/var/lib/postgresql/data`, init SQL (ro) | `pg_isready -U eip` | — | 2.0 / 4g |
| redis | `redis:7-alpine` | Cache, Redisson locks, rate-limit state | `6379` internal only | `eip_redis_data:/data` (AOF) | `redis-cli ping` | — | 0.5 / 512m |
| kafka | `apache/kafka:3.7.0` | Event backbone, single-broker KRaft | `9092` internal; `29092:29092` optional host listener for debugging | `eip_kafka_data:/var/lib/kafka/data` | broker API check via `kafka-broker-api-versions.sh` | — | 1.5 / 2g |
| minio | `minio/minio:RELEASE.2024-06-13T22-53-53Z` | S3-compatible object storage (artifacts, ingested files) | `9000` internal; `9001:9001` console optional | `eip_minio_data:/data` | `mc ready local` | — | 1.0 / 1g |
| minio-init | `minio/mc` (one-shot) | Creates buckets `eip-artifacts`, `eip-ingest`; applies retention policy | none | none | exit code | minio (healthy) | 0.2 / 128m |
| keycloak | `quay.io/keycloak/keycloak:24.0` | OIDC provider (on-prem default IdP) | `8180` internal; proxied at `/auth` by frontend | `eip_keycloak_data` + realm import (ro) | `GET /health/ready` (mgmt port 9000) | postgres (healthy; uses schema `keycloak` in the same instance) | 1.0 / 1.5g |
| otel-collector | `otel/opentelemetry-collector-contrib:0.102.0` | OTLP intake from app/workers; metric export to Prometheus | `4317`, `4318` internal only | `otel/collector.yaml` (ro) | `GET :13133/` (health ext.) | — | 0.5 / 512m |
| prometheus | `prom/prometheus:v2.53.0` | Metrics TSDB (15d retention default) | `9090` internal; publish only if needed | `eip_prom_data:/prometheus`, config (ro) | `GET /-/ready` | otel-collector (started) | 1.0 / 2g |
| grafana | `grafana/grafana:11.1.0` | Self-observability dashboards (provisioned from `/infra/grafana`) | `3001:3000` | `eip_grafana_data`, provisioning (ro) | `GET /api/health` | prometheus (started) | 0.5 / 512m |
| ollama (optional) | `ollama/ollama:0.3.9` | Local LLM + embedding serving for air-gapped AI | `11434` internal only | `eip_ollama_models:/root/.ollama` | `GET /api/version` | — | 4.0 / 8g (model-dependent) |
| qdrant (optional) | `qdrant/qdrant:v1.9.7` | Alternative vector store behind the VectorStore SPI (`EIP_VECTORSTORE_PROVIDER=qdrant`); pgvector remains the default | `6333`, `6334` internal only | `eip_qdrant_data:/qdrant/storage` | `GET /readyz` | — | 1.0 / 2g |

Scaling contract: `eip-workers` is the only horizontally scaled service — `docker compose up -d --scale eip-workers=3`. Worker replicas share Kafka consumer groups, so scaling is safe and partition-bound (topic partition counts, default 12, cap useful parallelism). `eip-app` runs as a single replica under compose; see Section 12 for why.

## 3. Annotated Compose Excerpt (specification for `compose.yaml`)

The following excerpt is illustrative but **normative**: the future `compose.yaml` must implement these services exactly as shown (remaining services follow the same patterns per the table above).

```yaml
services:
  eip-migrate:
    image: eip/eip-app:${EIP_VERSION}
    profiles: ["core"]
    command: ["migrate"]                    # entrypoint mode: run Flyway, then exit 0
    environment:
      EIP_DB_URL: jdbc:postgresql://postgres:5432/eip
      EIP_DB_USERNAME: ${EIP_DB_USERNAME}
      EIP_DB_PASSWORD: ${EIP_DB_PASSWORD}
    depends_on:
      postgres: { condition: service_healthy }
    restart: "no"                           # a failed migration must halt the rollout

  eip-app:
    image: eip/eip-app:${EIP_VERSION}
    profiles: ["core"]
    environment:
      SPRING_PROFILES_ACTIVE: compose       # structured JSON logs, OTLP on, container hostnames
      EIP_DB_URL: jdbc:postgresql://postgres:5432/eip
      EIP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      EIP_REDIS_URL: redis://redis:6379
      EIP_S3_ENDPOINT: http://minio:9000
      EIP_OIDC_ISSUER_URI: ${EIP_PUBLIC_URL}/auth/realms/eip   # public issuer via frontend proxy
      EIP_SECRETS_MASTER_KEY_FILE: /run/secrets/eip_master_key # never the inline env variant here
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
    secrets: [eip_master_key]
    depends_on:
      eip-migrate: { condition: service_completed_successfully }  # schema before app
      postgres:   { condition: service_healthy }
      kafka:      { condition: service_healthy }
      redis:      { condition: service_healthy }
      minio:      { condition: service_healthy }
      keycloak:   { condition: service_healthy }
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8081/actuator/health/readiness"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 60s                     # JVM + context startup allowance
    deploy:
      resources: { limits: { cpus: "2.0", memory: 4g } }
    restart: unless-stopped

  kafka:
    image: apache/kafka:3.7.0
    profiles: ["core"]
    environment:                            # single-node KRaft: broker + controller combined
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_NODE_ID: "1"
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"   # single broker: RF must be 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"      # topics created by eip-migrate topic step
      KAFKA_LOG_RETENTION_HOURS: "168"
    volumes: [eip_kafka_data:/var/lib/kafka/data]
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 18
      start_period: 30s
    restart: unless-stopped

  postgres:
    image: pgvector/pgvector:pg16           # pgvector baked in; plain postgres:16 is NOT valid
    profiles: ["core"]
    environment:
      POSTGRES_DB: eip
      POSTGRES_USER: ${EIP_DB_USERNAME}
      POSTGRES_PASSWORD: ${EIP_DB_PASSWORD}
    volumes:
      - eip_pg_data:/var/lib/postgresql/data
      - ./postgres/00-extensions.sql:/docker-entrypoint-initdb.d/00-extensions.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${EIP_DB_USERNAME} -d eip"]
      interval: 5s
      timeout: 3s
      retries: 20
    restart: unless-stopped

secrets:
  eip_master_key:
    file: ${EIP_SECRETS_MASTER_KEY_PATH}    # operator-provided 32-byte base64 key file
```

## 4. Startup Orchestration

Order is enforced entirely by healthcheck-gated `depends_on`; no sleep loops or external scripts:

1. Stateful services start in parallel: postgres, redis, kafka, minio, keycloak (keycloak waits on postgres).
2. `eip-migrate` runs once postgres is healthy: Flyway migrations, then Kafka topic creation for all `eip.*` topics (idempotent, with the DLQ-per-consumer-group naming from the event model). Exit 0 gates everything downstream; a non-zero exit stops the rollout with the schema untouched-or-forward, never half-applied (Flyway transactional migrations).
3. `eip-app` and `eip-workers` start after `eip-migrate` completes successfully and all infra healthchecks pass.
4. `frontend` starts after `eip-app`; the optional `eip-sim-seed` job (simulation profile) runs last, calling the API to create the simulation connector and trigger a full sync.

`restart: unless-stopped` on all long-running services makes the stack self-heal across host reboots; one-shot jobs use `restart: "no"`.

## 5. Environment Variable Reference (`.env` contract)

Grouped by concern. Names are identical to the local-dev and Kubernetes contracts.

| Group | Variable | Default in `.env.example` | Notes |
|---|---|---|---|
| General | `EIP_VERSION` | pinned release tag | Application image tag (app, workers, frontend move together). |
| General | `EIP_PUBLIC_URL` | `https://eip.example.com` | External base URL; used for OIDC redirect URIs and issuer. |
| DB | `EIP_DB_USERNAME` / `EIP_DB_PASSWORD` | `eip` / `CHANGE_ME` | Also consumed by the postgres container itself. |
| Kafka | `EIP_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Change only when pointing at an external cluster. |
| Redis | `EIP_REDIS_URL` | `redis://redis:6379` | |
| MinIO | `EIP_S3_ACCESS_KEY` / `EIP_S3_SECRET_KEY` | `CHANGE_ME` / `CHANGE_ME` | Root credentials for the bundled MinIO; scoped keys for external S3. |
| MinIO | `EIP_S3_ENDPOINT` | `http://minio:9000` | Any S3-compatible endpoint is valid. |
| OIDC | `EIP_OIDC_ISSUER_URI` | `${EIP_PUBLIC_URL}/auth/realms/eip` | Point at enterprise IdP (AD FS/Azure AD/Okta) to bypass bundled Keycloak. |
| OIDC | `EIP_OIDC_CLIENT_ID` / `EIP_OIDC_CLIENT_SECRET` | `eip-backend` / `CHANGE_ME` | |
| OIDC | `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `CHANGE_ME` | Bundled Keycloak bootstrap admin. |
| Secrets | `EIP_SECRETS_MASTER_KEY_PATH` | `./secrets/master.key` | Host path to the 32-byte base64 AES-256-GCM master key file, mounted via compose `secrets:`. Generate with `openssl rand -base64 32`. The inline `EIP_SECRETS_MASTER_KEY` env variant is rejected outside the `local` profile. |
| LLM | `EIP_LLM_PROVIDER` | `ollama` | `ollama`, `vllm`, `openai-compatible`, `anthropic-compatible`, `custom`. |
| LLM | `EIP_LLM_BASE_URL` | `http://ollama:11434` | External endpoint URL when not using the `ai-local` profile. |
| LLM | `EIP_LLM_API_KEY` | empty | Required for remote providers; stored as a compose secret in hardened installs. |
| LLM | `EIP_EMBEDDINGS_MODEL` | `nomic-embed-text` | RAG embedding model. |
| LLM | `EIP_VECTORSTORE_PROVIDER` | `pgvector` | `qdrant` requires the qdrant service. |
| Observability | `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4318` | App/workers export traces/metrics/logs here. |
| Observability | `GF_SECURITY_ADMIN_PASSWORD` | `CHANGE_ME` | Grafana admin. |
| Workers | `EIP_WORKER_PROFILES` | `ingestion,analytics,ai,reports` | All groups in one worker container by default; split across differently-configured replicas for larger installs. |

## 6. Persistence and Volume Strategy

Named volumes only (no bind mounts for data), so `docker compose down` without `-v` is always non-destructive:

| Volume | Service | Content | Growth driver |
|---|---|---|---|
| `eip_pg_data` | postgres | Canonical model, raw staging JSONB, pgvector embeddings, audit | Connector history depth; largest volume |
| `eip_kafka_data` | kafka | Topic log segments (7-day retention) | Ingestion event rate |
| `eip_minio_data` | minio | Generated artifacts, ingested files/blobs | Report generation, document ingestion |
| `eip_redis_data` | redis | AOF (cache/locks; loss is tolerable, avoids cold-start stampede) | Bounded |
| `eip_keycloak_data` | keycloak | Realm, users (when bundled Keycloak is the IdP) | Bounded |
| `eip_prom_data`, `eip_grafana_data` | prometheus, grafana | Metrics TSDB, dashboard state | Retention setting |
| `eip_ollama_models`, `eip_qdrant_data` | ollama, qdrant | Model blobs; vector collections | Model count; corpus size |

## 7. Backup

Compose installs are single-host; backups are the only disaster-recovery mechanism. The provided `backup/eip-backup.sh` (spec):

1. `pg_dump -Fc` of the `eip` database (includes pgvector data and the Keycloak schema when co-hosted) — the primary backup; taken online.
2. `mc mirror` of MinIO buckets to the backup target.
3. Kafka is **not** backed up: topics are re-derivable (raw staging is in Postgres/MinIO) and consumers checkpoint in Postgres. After restore, offsets reset per the checkpoint table.
4. Tar of `/infra/docker-compose/.env`, secrets files, TLS material — stored encrypted, separately from data backups.

Recommended cadence: nightly `pg_dump` + MinIO mirror, retained 14 days minimum. Restore drill (dump → fresh host → `up` → smoke test, Section 14) is part of the production readiness bar for any compose install kept beyond evaluation.

## 8. TLS Termination

Default: the `frontend` nginx terminates TLS on 443 using operator-provided certificates.

- Mount cert/key at `nginx/tls/tls.crt` and `nginx/tls/tls.key`; set `EIP_PUBLIC_URL` to the https URL. `frontend.conf` redirects 80 → 443, serves the SPA, and proxies `/api` → `eip-app:8080` and `/auth` → `keycloak:8180` (with correct `X-Forwarded-*` headers so Keycloak and the app generate https URLs).
- Alternative: run the stack behind an existing enterprise reverse proxy/LB; leave the frontend on port 80 bound to localhost and let the external proxy terminate TLS. The only requirement is that `X-Forwarded-Proto`/`Host` reach nginx intact.
- Internal (container-to-container) traffic is plaintext on the compose network; the network is not host-exposed. Installs requiring encrypted internal hops must graduate to Kubernetes (Section 12).

## 9. Upgrade Procedure

EIP releases are upgrade-safe between consecutive minor versions (expand-contract migrations; see `./KubernetesOpenShift.md` Section 13 for the schema policy).

1. Read the release notes; back up (Section 7) — mandatory before any upgrade.
2. Update `EIP_VERSION` in `.env`; `docker compose pull` (or load the air-gapped bundle, Section 11).
3. Stop application layer only: `docker compose stop frontend eip-app eip-workers` (infra keeps running; downtime window starts).
4. `docker compose up -d` — recreates `eip-migrate` first (new image runs Flyway forward), then app, workers, frontend in dependency order.
5. Verify: migration job exit 0, app readiness `UP`, smoke test (Section 13). Typical downtime: 1–3 minutes.
6. Rollback: application images are backward-compatible with the expanded schema for one version — restore `EIP_VERSION`, `up -d` again. If a contract migration already ran (called out in release notes), rollback requires the pre-upgrade `pg_dump`.

Infrastructure images (postgres, kafka, keycloak, etc.) are upgraded independently and deliberately, never as a side effect of an EIP release.

## 10. Sizing Guidance

| Install shape | vCPU | RAM | Disk (initial) | Notes |
|---|---|---|---|---|
| Demo / evaluation, simulation data, no local LLM | 4 | 8 GB | 50 GB SSD | `core` (+ `simulation`); AI features pointed at an external endpoint or disabled. |
| Small production, external LLM endpoint | 8 | 16 GB | 200 GB SSD | `core` + `observability`, 2 worker replicas. |
| Full stack with local LLM (`ai-local`) | **8 (minimum)** | **16 GB (minimum)**, 24 GB comfortable | 300 GB SSD | Ollama with an 8B-class model; a GPU is strongly recommended for acceptable agent latency, CPU-only works for evaluation. |

Postgres disk is the growth axis — plan ~1–2 GB per 100 active engineers per month of retained history (connector-mix dependent, dominated by SCM and CI/CD events).

## 11. Air-Gapped Install

EIP releases ship an offline bundle `eip-offline-<version>.tar.gz` containing:

- `images.tar` — every pinned image in the catalog (`docker save` output), including optional-profile images and the default Ollama model blobs as an OCI artifact.
- `infra/docker-compose/` tree (compose file, configs, `.env.example`), release notes, checksums (`sha256sums.txt`, signed).

Procedure on the target host (no internet access required at any step):

```bash
sha256sum -c sha256sums.txt
docker load -i images.tar
ollama_models/load.sh          # copies model blobs into the eip_ollama_models volume
cp .env.example .env           # edit: secrets, EIP_PUBLIC_URL, TLS paths
openssl rand -base64 32 > secrets/master.key && chmod 600 secrets/master.key
docker compose --profile core --profile observability --profile ai-local up -d
```

Air-gapped installs must set `EIP_LLM_PROVIDER=ollama` (or point at an in-network vLLM); the platform has no other outbound dependencies by design (on-premise-first, per `../vision/Vision.md`).

## 12. Limitations vs Kubernetes — When to Graduate

Accepted limitations of the compose deployment:

- **No HA anywhere:** single Postgres, single Kafka broker (RF=1), single Redis, single `eip-app`. Any component failure is an outage until restart; host failure requires restore from backup.
- **Vertical scaling only** (except `eip-workers --scale`, which is still bound to one host's resources). No autoscaling — worker throughput under backlog is fixed.
- Single-host blast radius, plaintext internal network, no zero-downtime deploys (Section 9's window), no PDB/anti-affinity/zone-spread concepts.
- `eip-app` is single-replica by design here: session-less and lock-safe (Redisson), but compose provides no load balancing or rolling replacement, so multiple replicas add risk without availability benefit.

Graduate to Kubernetes/OpenShift (`./KubernetesOpenShift.md`) when any of these hold: availability target above best-effort (~99%), more than ~500 tracked engineers or sustained Kafka consumer lag with workers already scaled to host capacity, compliance requiring encrypted internal traffic/NetworkPolicy/secret-manager integration, or organizational need for rolling zero-downtime upgrades. Migration path: `pg_dump`/`mc mirror` out of the compose volumes into operator-managed Postgres/MinIO; Kafka state is not migrated (checkpoints replay).

## 13. Post-Install Smoke Test Checklist

- [ ] All containers healthy: `docker compose ps` shows every `core` service `running (healthy)`; `eip-migrate` and `minio-init` `exited (0)`.
- [ ] `curl -k https://<host>/healthz` returns 200 (frontend nginx).
- [ ] `docker compose exec eip-app curl -fsS localhost:8081/actuator/health` reports `UP` with `db`, `kafka`, `redis`, `s3`, `oidc` component details `UP`.
- [ ] OpenAPI reachable: `https://<host>/api/v1/docs` renders.
- [ ] Login round-trip: browser → `EIP_PUBLIC_URL` → Keycloak (or enterprise IdP) → dashboard shell loads for an admin user.
- [ ] Connector path: create a Simulation connector (`demo-small` pack), run full sync, verify it reaches `COMPLETED` and the checkpoint advances.
- [ ] Event path: `eip.domain.workitem` has messages (`kafka-console-consumer` with `--max-messages 1`).
- [ ] Dashboard renders non-empty sprint/flow charts for the seeded data.
- [ ] AI path (if enabled): Admin → AI → provider test returns model list; run the Sprint Review agent on the demo sprint; verify the LLM call audit record (model, tokens, latency) appears.
- [ ] Report path: generate a sprint report; artifact appears in the artifact library (stored in `eip-artifacts` bucket).
- [ ] Observability (if enabled): Grafana shows the EIP overview dashboard with live JVM/Kafka/HTTP panels.
- [ ] Backup script completes and produces a restorable `pg_dump` (verify with `pg_restore --list`).
