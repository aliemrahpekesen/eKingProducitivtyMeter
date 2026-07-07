# Security Model

This document defines the threat-model-driven security architecture of the Engineering Intelligence Platform (EIP). EIP is an on-premise, multi-tenant, AI-native platform that ingests sensitive SDLC data (work items, source-control metadata, quality findings, incidents) from enterprise tools; its security model must protect that data, the credentials used to obtain it, and the AI pipeline that reasons over it. A core product constraint shapes the model throughout: EIP is **not** an individual-surveillance tool, and the security model enforces that stance technically (role-gated individual-level signals, pseudonymization, audited access).

Related documents: `../architecture/DeploymentModel.md` (network zones, TLS), `../architecture/ObservabilityModel.md` (audit/trace correlation), `../operations/OperationsGuide.md` (runbooks).

## 1. Assets and Trust Boundaries

Primary assets, in priority order:

| # | Asset | Why it matters |
|---|---|---|
| A1 | Connector credentials (tool tokens, webhook secrets) | Grant read access to enterprise Jira/GitHub/SonarQube/etc.; highest-value theft target |
| A2 | Ingested SDLC data (canonical model, `raw_*` staging, object storage blobs) | Confidential engineering data; cross-tenant leakage is the worst-case product failure |
| A3 | Developer identity data (Member records, ExternalRef identity mappings) | PII; misuse enables surveillance, violating product anti-goals and privacy law |
| A4 | RAG corpus and vector embeddings | Derived confidential data; retrievable via AI features |
| A5 | LLM prompts/outputs and agent tool-call traces | May embed A2/A3 content; egress risk with external providers |
| A6 | Audit log | Integrity target for attackers covering tracks |
| A7 | Platform availability | Executive reporting and delivery-risk workflows depend on it |
| A8 | KMS master key / data keys | Compromise unwraps A1 |

```mermaid
flowchart TB
    subgraph TB1["Trust boundary 1: User network → Edge"]
        USER["Users / API clients<br/>(OIDC tokens, service tokens)"]
    end
    subgraph TB2["Trust boundary 2: EIP application zone"]
        APP["eip-app (REST /api/v1)<br/>authn, RBAC, tenant scoping"]
        WORKERS["eip-workers<br/>ingestion / analytics / ai / reports"]
    end
    subgraph TB3["Trust boundary 3: EIP data zone"]
        PG[("PostgreSQL 16<br/>RLS per tenant_id, pgvector")]
        KAFKA[("Kafka 'eip.' topics")]
        REDIS[("Redis 7")]
        S3[("MinIO / S3")]
        AUDIT[("Audit log<br/>hash-chained")]
    end
    subgraph TB4["Trust boundary 4: Enterprise tools (semi-trusted)"]
        TOOLS["Jira, GitHub, SonarQube, CI/CD, ...<br/>+ enterprise MCP servers"]
    end
    subgraph TB5["Trust boundary 5: LLM endpoints"]
        LLM["Local Ollama/vLLM (trusted infra)<br/>or external provider (untrusted for data egress)"]
    end
    KC["Keycloak / enterprise IdP"]
    VAULT["KMS: env/file/Vault"]
    USER -->|"TLS + JWT"| APP
    TOOLS -->|"signed webhooks"| APP
    APP --> PG & KAFKA & REDIS & S3 & AUDIT
    WORKERS --> PG & KAFKA & S3 & AUDIT
    WORKERS -->|"scoped credentials"| TOOLS
    WORKERS -->|"policy-gated"| LLM
    APP --> KC
    APP & WORKERS --> VAULT
```

Trust decisions: ingested tool content (TB4) and LLM output (TB5) are treated as **untrusted input** even though the tools are enterprise-internal — this drives the prompt-injection and output-validation controls in Section 8. Tenants are mutually untrusted within one deployment.

## 2. STRIDE Summary

| Threat | Representative scenario | Mitigations |
|---|---|---|
| **S**poofing | Forged webhook posts fake CI events; stolen service token calls API | Webhook HMAC signature validation per connector + per-connector secret; OIDC JWT validation (issuer, audience, expiry, JWKS); service tokens hashed at rest, scoped, expiring; mTLS optional at ingress |
| **T**ampering | Attacker mutates canonical data or audit trail; Kafka message alteration | RLS + least-privilege DB roles; TLS on all links; audit log hash chaining (§11); event envelope `eventId` (UUIDv7) + idempotent consumers reject replays; image signing (§10) |
| **R**epudiation | Admin denies exporting a report or reading a secret | Audit event taxonomy covers every sensitive action with actor, tenant, `traceId`; audit is append-only and tamper-evident |
| **I**nformation disclosure | Cross-tenant read; secret leak in logs/UI; RAG retrieval returning another team's documents; prompts sent to external LLM | Postgres RLS on `tenant_id` + application-layer tenant scoping (defense in depth); AES-256-GCM envelope encryption for secrets; UI masking; log redaction; permission-aware RAG filters (§5); LLM egress policy (§8) |
| **D**enial of service | Connector floods ingestion; runaway agent burns tokens; API abuse | Rate limiting per token/tenant (Redis-backed); Kafka backpressure + DLQ per consumer group; agent budgets (token/cost/time); HPA bounds; request size limits |
| **E**levation of privilege | MEMBER escalates to TENANT_ADMIN; prompt injection makes agent call privileged tool | RBAC with deny-by-default permission checks at controller and service layers; per-agent capability sandboxing and tool allow-lists (§8); MCP per-capability RBAC (§9); no dynamic role grants without TENANT_ADMIN + audit |

## 3. Authentication

- **Primary: OIDC.** Keycloak on-prem by default; pluggable enterprise IdP (AD FS, Azure AD, Okta) via standard OIDC. Authorization Code + PKCE for the React frontend; the backend validates JWTs (issuer, audience, signature via JWKS, expiry, clock skew ≤ 60 s). Group/role claims can be mapped to EIP roles per tenant (claim-mapping config).
- **Local accounts fallback.** For air-gapped bootstrap and break-glass: Argon2id password hashing, configurable password policy, lockout with exponential backoff, mandatory MFA-capable (TOTP) for PLATFORM_ADMIN local accounts. Local login can be disabled per deployment once OIDC is live, except one break-glass admin.
- **Service tokens for API automation.** Long-lived credentials for CI/scripts: created by TENANT_ADMIN (tenant-scoped) or PLATFORM_ADMIN (platform-scoped), bound to a role + optional permission subset, stored as SHA-256 hash, prefix-identifiable (`eipt_...`), expiring (default 90 days, max 365), revocable immediately, last-used timestamp tracked, creation/revocation/use audited.
- **Session policy.** Access tokens ≤ 15 min, refresh via OIDC (refresh token rotation, reuse detection at the IdP); idle timeout 30 min and absolute session lifetime 12 h enforced platform-side; concurrent session limit configurable; logout triggers OIDC back-channel logout where supported. Cookies (if used for the SPA BFF mode): `Secure`, `HttpOnly`, `SameSite=Lax`, CSRF token on mutating requests.

Authentication and per-request enforcement flow:

```mermaid
sequenceDiagram
    participant U as User (React SPA)
    participant KC as Keycloak / enterprise IdP
    participant A as eip-app (/api/v1)
    participant PG as PostgreSQL (RLS)
    participant AU as Audit log
    U->>KC: Authorization Code + PKCE
    KC-->>U: ID/access token (≤15 min) + refresh token
    U->>A: GET /api/v1/... (Bearer JWT)
    A->>A: validate JWT (issuer, audience, signature via cached JWKS, expiry)
    A->>A: resolve TenantContext + effective permissions (roles → permission catalog)
    A->>A: endpoint permission check, then resource-level scope check
    A->>PG: SET LOCAL app.tenant_id = :tenant; query
    PG-->>A: rows filtered by RLS policy (backstop)
    A-->>U: 200 response
    A->>AU: audit event on sensitive action (actor, target, outcome, traceId)
    Note over A,AU: 403 paths emit access.denied with the same correlation fields
```

Service tokens follow the same request path from the JWT-validation step onward: the token hash is looked up, expiry/revocation checked, and its bound role + permission subset becomes the effective permission set.

## 4. Authorization — RBAC

Deny-by-default RBAC: roles bundle fine-grained permissions; every `/api/v1` endpoint declares required permissions; service-layer checks repeat the enforcement (defense in depth against controller gaps).

Canonical roles follow the role model of [../product/Personas.md](../product/Personas.md) §1 (FEAT-004): four built-in base roles plus six seeded persona role templates composed on the `ANALYST`/`VIEWER` bases, provisioned at tenant creation (UC-001) and editable per tenant.

| Role | Kind | Scope | Intended holder | Summary |
|---|---|---|---|---|
| `PLATFORM_ADMIN` | Base | Platform (all tenants) | Platform operators | Deployment-wide config, tenant lifecycle, KMS operations, platform health. Cannot silently read tenant business data: cross-tenant data access requires explicit, audited support-access grant |
| `TENANT_ADMIN` | Base | Tenant | Customer IT owner | Tenant config, connectors + secrets, users/roles, Organization/BusinessUnit/Team structure and member mappings, AI/LLM policy, MCP allow-lists, retention settings |
| `ANALYST` | Base | Granted scope | Analytics consumer | Dashboards, drill-downs, RAG queries, report generation within permitted scope |
| `VIEWER` | Base | Granted scope | Stakeholder | Read-only dashboards and published reports |
| `ENGINEERING_MANAGER` | Seeded template | Team(s)/Project(s) | EM / delivery manager | Team-level metrics and reports, sprint/kanban analytics, delivery-risk views, report generation for owned scope |
| `TEAM_LEAD` | Seeded template | Own team(s) | Team lead | Team dashboards, flow analytics, generated sprint reviews for owned teams |
| `MEMBER` | Seeded template | Own teams | Engineer | Team dashboards, own work-item context, RAG queries within permitted scope |
| `RELEASE_MANAGER` | Seeded template | Release scope | Release/delivery manager | Release-readiness and release dashboards, release-notes generation for owned releases |
| `EXECUTIVE_VIEWER` | Seeded template | Org-level rollups | Executive | Org-level rollups, executive summaries, scheduled report consumption |
| `SECURITY_AUDITOR` | Seeded template | Tenant (read-only) | Security/compliance | Read audit log, security config, retention evidence; no business-data mutation, no secret values |

Permission catalog (representative; the catalog is the authoritative enum in `eip-tenancy`; manager-scope templates = `ENGINEERING_MANAGER`/`TEAM_LEAD`/`RELEASE_MANAGER`):

| Permission | PLATFORM_ADMIN | TENANT_ADMIN | Manager-scope templates | ANALYST | MEMBER | VIEWER / EXECUTIVE_VIEWER | SECURITY_AUDITOR |
|---|---|---|---|---|---|---|---|
| `tenant.manage` | ✓ | ✓ | — | — | — | — | — |
| `user.manage` / `role.assign` | ✓ | ✓ | — | — | — | — | — |
| `connector.configure` | — | ✓ | — | — | — | — | — |
| `connector.secret.write` | — | ✓ | — | — | — | — | — |
| `connector.secret.reveal` | — | opt-in, audited | — | — | — | — | — |
| `dashboard.view` | — | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `metric.individual.view` (individual-level signals) | — | policy-gated | — | — | — | — | — |
| `report.generate` | — | ✓ | ✓ | ✓ | — | — | — |
| `report.export` | — | ✓ | ✓ | ✓ | — | scope-gated | — |
| `ai.agent.invoke` | — | ✓ | ✓ | ✓ | ✓ (subset) | — | — |
| `ai.policy.manage` (models, budgets, egress) | — | ✓ | — | — | — | — | — |
| `mcp.capability.invoke:<capability>` | — | per allow-list | per allow-list | — | — | — | — |
| `audit.read` | ✓ (platform events) | ✓ | — | — | — | — | ✓ |
| `retention.manage` / `erasure.execute` | — | ✓ | — | — | — | — | — |
| `platform.operate` (health, upgrades, KMS rotate) | ✓ | — | — | — | — | — | — |

Enforcement layers:

1. **Tenant scoping.** Every request resolves a `TenantContext` from the token; every table carries `tenant_id` and Postgres Row-Level Security policies filter on the tenant bound via transaction-scoped `SET LOCAL app.tenant_id` (policies read `current_setting('app.tenant_id')`; never session-scoped, so connection pooling stays safe). Application code never queries without tenant context; RLS is the backstop if it does. Kafka consumers propagate `tenantId` from the event envelope into the same context.
2. **Resource-level checks.** Beyond role checks, ownership/scope checks apply per resource: an ENGINEERING_MANAGER on Team A cannot read Team B's delivery-risk detail; report artifacts and GeneratedReport records carry an access scope evaluated on download; connectors and secrets are tenant-bound objects.
3. **Permission-aware RAG retrieval.** Vector search (pgvector/Qdrant via VectorStore SPI) always applies: (a) tenant isolation (hard filter, enforced in the SPI, not the caller), (b) metadata filters derived from the caller's permissions and scope (source system, project/team visibility, document ACL where the source tool exposes one), (c) result-side re-check before chunks enter the prompt. An agent acting for a user retrieves with that user's effective permissions — never with a service-level superuser context. Retrievals are audit-logged (§11).

## 5. Multi-Tenancy Isolation Summary

| Layer | Mechanism |
|---|---|
| Database | `tenant_id` column + Postgres RLS on every tenant table; per-transaction `SET LOCAL app.tenant_id` binding; Flyway-managed policies |
| Vector store | Three defense-in-depth layers (ADR-016, stated identically in `../ai/RAGArchitecture.md` §9): (1) store-level isolation — pgvector (default): Postgres RLS on `rag_chunk` and the per-model embedding tables; Qdrant (pluggable): **one collection per tenant per embedding space**, named `eip_<tenantId>_<embeddingSpace>`; (2) tenant filter enforced inside the VectorStore SPI on every operation (unfiltered queries refused); (3) result-side tenant/ACL recheck before hits leave the retrieval API (`../ai/RAGArchitecture.md` §8) |
| Kafka | `tenantId` in every event envelope; consumers validate envelope tenant against target rows; ordering key `tenantId:entityId` on domain topics (key composition varies by topic family, see `../engineering/EventModel.md` §7) |
| Object storage | One mechanism (ADR-018): shared buckets (`eip-ingest`, `eip-artifacts`) + mandatory tenant-id key prefix (`<tenantId>/...`), scoping enforced by the single application storage service (every read/write path resolves the prefix from the tenant context, never from caller input), with object-access audit. Per-tenant MinIO credentials/policies are **not** used — an accepted risk, mitigated by the periodic storage-prefix isolation test in the NFR-041 isolation suite (§13) |
| Cache | Redis key namespace `eip:{tenantId}:...`; no cross-tenant key access in code review checklist |
| AI | Per-tenant model routing, token budgets, RAG isolation, per-tenant MCP allow-lists |
| Rate limits | Per-tenant and per-token quotas |

## 6. Secret Management

- **Envelope encryption.** Every stored secret (connector tokens, webhook secrets, LLM API keys, SMTP credentials) is encrypted with AES-256-GCM using a per-secret data encryption key (DEK); DEKs are wrapped by the master key from the KMS SPI. Ciphertext records: key id, wrap algorithm, nonce, AAD (`tenantId + secretId`), created/rotated timestamps. Secrets are never stored or logged in plaintext.

```mermaid
flowchart LR
    subgraph WRITE["Store secret (TENANT_ADMIN)"]
        PT["plaintext secret<br/>(write-only API field)"] --> ENC["AES-256-GCM encrypt<br/>fresh DEK + nonce,<br/>AAD = tenantId + secretId"]
        ENC --> CT["ciphertext + metadata<br/>→ Postgres (tenant row, RLS)"]
        ENC --> WRAP["wrap DEK"]
    end
    subgraph KMS["KMS SPI"]
        MK["master key<br/>env (demo) / file / Vault Transit"]
    end
    WRAP <--> MK
    WRAP --> WDEK["wrapped DEK<br/>stored beside ciphertext"]
    subgraph READ["Use secret (worker: connector sync / LLM call)"]
        WDEK2["wrapped DEK"] --> UNWRAP["unwrap via KMS SPI"]
        UNWRAP <--> MK
        UNWRAP --> DEC["AES-256-GCM decrypt<br/>(AAD verified)"]
        DEC --> MEM["plaintext in memory only,<br/>zeroed after use"]
    end
    MEM -. every decrypt .-> AUD["audit: secret.access<br/>(actor, purpose, traceId)"]
```
- **KMS SPI providers.** `env` (master key from environment — demo only), `file` (mounted key file, permissions-checked at startup), `vault` (HashiCorp Vault Transit for wrap/unwrap; key never leaves Vault). Provider is deployment-selected; the SPI allows enterprise HSM adapters later.
- **Rotation procedure.** (1) Master key rotation: introduce new key version → background job re-wraps all DEKs (no data re-encryption needed) → retire old version after re-wrap completes; both versions valid during the window; progress observable via metric and audit events. (2) Secret value rotation: TENANT_ADMIN updates a connector secret; old value overwritten (previous ciphertext retained for one grace period only if the connector supports dual credentials); `connector.testConnection()` validates before commit.
- **Compromise recovery (distinct from routine rotation).** Re-wrapping DEKs is cryptographically insufficient after a master-key compromise: an attacker holding the old master key plus the ciphertext has already unwrapped the DEKs, so the DEKs themselves — and everything they protect — must be treated as exposed. On suspected or confirmed compromise: (1) generate a new master key version; (2) generate **fresh DEKs and re-encrypt every stored secret** under them (never re-wrap-only); (3) rotate all downstream credentials the old DEKs protected — connector tokens, webhook secrets, LLM provider keys, MCP server credentials, SMTP credentials — at their sources; (4) verify by count: the number of re-encrypted secrets and rotated downstream credentials is reconciled against the secret inventory, and the reconciliation result is recorded; (5) every phase emits audit events (`kms.compromise.recovery.started/completed`, per-secret re-encryption and rotation events). The operational runbook lives in `../operations/OperationsGuide.md` §3.3; the OperationsGuide §12 "on suspicion of compromise" row routes to **this** procedure, not to the routine re-wrap path.
- **UI masking.** Secret values are write-only in the UI/API: displayed as `••••` with last-4 hint where safe; `connector.secret.reveal` is a distinct, default-disabled, always-audited permission. API responses never echo secret fields; OpenAPI marks them `writeOnly`.
- **Access audit.** Every decrypt is an audit event (`secret.access`) with actor (user or worker identity + purpose, e.g. `connector-sync:jira-prod`), secret id, and `traceId`. Anomalous decrypt patterns (volume, unfamiliar purpose) are alertable via the observability stack.

## 7. Data Protection

- **Encryption at rest.** Options by layer: Postgres — volume/filesystem encryption (LUKS/storage-class) as baseline, plus column-level AES-256-GCM for secrets (always) and optionally for identity-mapping tables; Kafka — encrypted volumes (broker-side); MinIO — SSE-S3/SSE-KMS; backups encrypted with a distinct key. At-rest encryption of infrastructure volumes is the deployer's storage-class choice and is documented in the hardening checklist.
- **TLS everywhere.** All internal and external links, minimum TLS 1.2 (prefer 1.3); no plaintext listener anywhere; see `../architecture/DeploymentModel.md` §12.
- **PII: developer identity data.** EIP stores Member records and `ExternalRef` identity mappings (sourceSystem, externalId, url) — this is PII. Controls: identity mapping tables are access-restricted (TENANT_ADMIN), individual-level signals are aggregated to team grain by default per the anti-surveillance stance, and every metric that could resolve to an individual is gated behind `metric.individual.view`, which is disabled by default and requires explicit tenant policy opt-in.
- **Pseudonymization option.** Per-tenant setting: individual-level signals are keyed by a salted pseudonym (`member_pseudo_id`) instead of the Member identity; the mapping table is separately encrypted and readable only by TENANT_ADMIN under audit. Dashboards, reports, and RAG chunks then carry pseudonyms for individual-grain data; team-level analytics (the default product surface: load balance, review bottlenecks, knowledge concentration) are unaffected.
- **Retention.** The table below is the platform's canonical retention policy (NFR-070). It is the single source of record for retention defaults: other documents (`../architecture/DataFlow.md` §9, `../architecture/DeploymentModel.md` §5) reference this section rather than restating values. All defaults are per-tenant configurable.

| Data class | Default retention | Notes |
|---|---|---|
| Raw staging (`raw_*` tables + object-storage blobs) | 90 days | NFR-070 default; per-tenant configurable |
| Canonical model | Indefinite | Tenant policy may shorten; 25 months is an example tenant policy (used as the representative sizing assumption in `../architecture/DeploymentModel.md` §5) |
| Metric series / aggregates | Indefinite | Tenant policy may shorten; 37 months of hot history is an example tenant policy (same sizing assumption) |
| AI/LLM call logs | 13 months | Stored under the §8 redaction policy |
| Audit log | 25 months minimum | Append-only, tenant-extendable (§11) |
| `processed_events` dedup ledger | 35 days | Must exceed the longest domain-topic retention (30 days) plus the replay window |

Kafka topics rely on broker retention expiry (topics are transport, not the store of record).

- **Erasure (FR-142).** Member erasure operates on the identity-mapping layer plus every store where direct PII can appear. Propagation list (per-store actions are specified alongside the schemas in `../engineering/DatabasePlan.md` and `DomainModel.md`; the erasure runbook is `../operations/OperationsGuide.md` §3.4):
  1. **Identity mapping** — the dedicated mapping store (`member_identity` / Member PII columns) is hard-deleted or crypto-shredded. Because every other pseudonymized store references members only by `memberId`/`member_pseudo_id` (audit log, metric facts), this single action pseudonymizes them irreversibly.
  2. **Canonical PII columns** carrying verbatim person text (e.g. `commit.message` author references, `work_item.description`) — targeted redaction/rewrite of the member's PII in place.
  3. **RAG chunks and embeddings** for member-authored content, where applicable — affected `rag_chunk` rows are re-chunked or deleted together with their vector entries.
  4. **Rendered report artifacts** containing the member's PII — deleted from object storage and the artifact index; reports remain regenerable from the retained, redacted inputs.
  5. **Caches** (retrieval, LLM response, dashboard) — invalidated by tenant-scoped key sweep.

  Audit rows and audit archives are never touched: they are pseudonymous by construction (§11), so erasure preserves the hash chain intact and verifiable. Every erasure run produces a **signed completion record** in the audit log containing: the erasure request id, tenant, pseudonymous `memberId`, the per-store action list with affected row/object counts, start and completion timestamps, the executing actor, and the hash of the detailed erasure manifest.

## 8. AI-Specific Security

The AI pipeline (agent runtime in `eip-ai`, RAG, MCP) treats all retrieved and tool-returned content as untrusted.

- **Prompt injection defenses (RAG/MCP content).** Retrieved chunks and MCP tool results are: (a) wrapped in delimited data blocks with explicit "data, not instructions" framing in system prompts; (b) sanitized (strip markup that mimics prompt structure, control characters, known injection markers); (c) never allowed to change the agent's tool allow-list, budgets, or system prompt — those are set by platform config only; (d) source-attributed, so an output influenced by a poisoned document is traceable to it. Injection handling at indexing time is **two-tier by detection confidence** (the same rule as `../ai/RAGArchitecture.md` §3.3): **high-confidence detections are quarantined** — not indexed, routed to an admin review queue, and audited; **low/medium-confidence detections are indexed with flags** and are only ever rendered to models with neutralized rendering, inside provenance-delimited untrusted data blocks.
- **LLM output validation.** Agent outputs pass through the Validation agent / structured validators before side effects: JSON-schema validation for structured outputs, citation checks (claims in narrative outputs must map to retrieved sources), numeric cross-checks against the metric engine for any quoted metric, and policy filters (no secrets patterns, no individual-ranking language per anti-goals). Invalid outputs are retried within budget, then failed with an auditable reason — never silently accepted.
- **Tool allow-lists and per-agent capability sandboxing.** Each canonical agent (Data Ingestion, Data Quality, Engineering Metrics, Delivery Risk, Sprint Review, Release Notes, Documentation, Use Case Diagram, Architecture Diagram, Executive Summary, Incident Analysis, Code Quality, Team Health, RAG Retrieval, Report Composition, Validation, Security Review, Configuration Assistant) declares a static capability manifest: callable tools, readable data domains, writable outputs, token/cost/time budgets. The runtime enforces the manifest; an agent cannot invoke a tool outside it regardless of model output. Agents execute with the invoking user's effective permissions intersected with the manifest.
- **Model/data egress controls.** Per-tenant LLM policy governs the provider SPI (Ollama, vLLM, OpenAI-compatible generic, Anthropic-compatible, custom enterprise endpoint): which providers are enabled, per-agent model routing, and an egress classification — tenants can restrict specific data classes (e.g. identity data, source snippets) to local providers only. When an external provider is enabled, prompts pass a redaction filter (identity pseudonymization, secret-pattern stripping) before egress, and the audit record marks the call as external.
- **Audit of every LLM call.** Prompt (redacted per policy), model, provider, token counts, cost estimate, latency, caller (user + agent), tenant, `traceId`, and validation verdict — persisted per call and surfaced in metrics (`eip_llm_tokens_total`, `eip_llm_cost_estimate`; see `../architecture/ObservabilityModel.md`).
- **Budgets as a security control.** Token/cost/time budgets per agent run and per tenant cap blast radius of runaway or adversarially-steered agents; exhaustion is an audited, alertable event.

## 9. MCP Security

EIP is both MCP client and MCP server; both directions are constrained.

- **As MCP client:** only TENANT_ADMIN-registered, allow-listed enterprise MCP servers are reachable (egress policy is generated from this list); each server's tools are imported into agent capability manifests explicitly (no wildcard tool adoption); tool results are untrusted input (§8 defenses apply); server credentials live in the secret store (§6).
- **As MCP server:** EIP exposes only explicitly allow-listed internal capabilities, under the canonical `eip.*` capability names of `../ai/MCPArchitecture.md` §3.2 (e.g. `eip.query_metrics`, `eip.query_work_items`, `eip.retrieve_citations`, `eip.list_generated_reports`, `eip.trigger_report_generation`); each capability has per-capability RBAC (`mcp.capability.invoke:<capability>`) evaluated against the authenticated caller's tenant-scoped identity; a capability absent from tenant configuration is disabled; capabilities are read-only unless individually justified; every invocation is audited with capability, caller, arguments digest, and `traceId`. Reads of the exposed MCP resources (`eip://runs/{runId}`, `eip://reports/{reportId}`) and `tools/list` enumerations are audited too (`mcp.server.resource_read`, `mcp.server.tools_listed` — §11).
- **MCP authentication:** `/api/v1/mcp` authenticates **platform service tokens only** — the same §3 service tokens (SHA-256-hashed high-entropy secrets, prefix-identifiable, default 90-day expiry, max 365). OIDC principals do not call the MCP endpoint in v1; they use the REST API.
- **Delegation model (v1: none).** The service token's principal is the **effective principal** for every capability call — there is no on-behalf-of. Named risk: **confused deputy** — a token shared by a multi-user assistant grants every downstream user the union of the token's read scope, including the ACL grants `eip.retrieve_citations` resolves for the token principal. Deployments must issue per-audience tokens (one per assistant/integration, minimally scoped); the admin UI warns when a token's grant scope exceeds a configured breadth threshold. On-behalf-of via token exchange is roadmap, not rejected — the token schema does not foreclose it (`../ai/MCPArchitecture.md` §3.1).
- **No transitive escalation:** an inbound MCP call cannot cause an outbound LLM call or MCP call beyond the caller's own permissions.

## 10. Supply Chain Security

| Control | Implementation |
|---|---|
| Image signing | All release images signed with cosign; keyless (CI OIDC) or enterprise key; admission policy (Kyverno/OpenShift image policy) verifies signatures + digests in-cluster; air-gapped installs verify at mirror time (`../architecture/DeploymentModel.md` §7) |
| SBOM | CycloneDX SBOM generated per image and per release archive; shipped with the release manifest; consumable by enterprise scanners |
| Dependency scanning | CI gates: OWASP Dependency-Check / Grype on every build; Renovate-managed updates; critical CVEs block release; base images rebuilt on a fixed cadence |
| Build integrity | Reproducible Gradle builds where feasible; provenance attestation (SLSA-style) attached to release artifacts; no build-time network access beyond the locked dependency mirror |
| Third-party models | Offline model bundles carry checksums in the release manifest; model files verified before load |

Runtime hardening baseline (all EIP containers, enforced by manifests in `/infra/kubernetes`):

- Non-root, arbitrary-UID-compatible images (OpenShift `restricted-v2` SCC); read-only root filesystem with explicit writable `emptyDir` mounts for temp/report scratch.
- `allowPrivilegeEscalation: false`, all capabilities dropped, seccomp `RuntimeDefault`.
- No shell/package-manager in production images (distroless-style JRE base where feasible).
- `NetworkPolicy` default-deny per namespace; egress generated from connector/LLM/MCP configuration (see `../architecture/DeploymentModel.md` §10).
- Resource requests/limits set on every container (DoS containment); JVM flags derive heap from container limits.
- Secrets mounted as files, never env vars; service account tokens not auto-mounted unless required.

## 11. Audit Logging

Event taxonomy (category → representative events):

| Category | Events (examples) | Notes |
|---|---|---|
| `auth` | `auth.login.success/failure`, `auth.token.issued/revoked`, `auth.session.expired`, `auth.breakglass.used` | Break-glass use pages on-call |
| `access` | `access.denied`, `secret.access`, `data.export`, `report.download`, `metric.individual.viewed` | Individual-level metric views always audited |
| `admin` | `tenant.created/updated`, `user.role.assigned`, `connector.configured`, `retention.changed`, `ai.policy.changed`, `mcp.allowlist.changed` | Config diffs recorded (secrets masked) |
| `secrets` | `secret.created/rotated/revealed`, `kms.key.rotated` | Reveal is distinct from access |
| `ai` | `llm.call`, `agent.run.started/completed/failed`, `rag.retrieval`, `agent.budget.exhausted`, `output.validation.failed` | See §8 |
| `mcp` | `mcp.capability.invoked`, `mcp.server.resource_read`, `mcp.server.tools_listed`, `mcp.server.registered`, `mcp.client.connected` | Both directions; resource reads (`eip://runs/{runId}`, `eip://reports/{reportId}`) and `tools/list` enumerations audited like tool calls (NFR-042) |
| `data` | `erasure.executed`, `retention.purge.completed`, `ingestion.replay.triggered` | Signed completion records |
| `platform` | `upgrade.applied`, `migration.executed`, `backup.completed/failed`, `support.access.granted` | PLATFORM_ADMIN actions |

Properties:

- **Schema.** Every event: `auditId (UUIDv7), occurredAt, tenantId, actor {type: USER|SERVICE_TOKEN|WORKER|AGENT, id}, category, event, target {type, id}, outcome, traceId, details (redacted)`. `traceId` links audit events to distributed traces (`../architecture/ObservabilityModel.md` §10). The `audit.audit_event` DDL (`../engineering/DatabasePlan.md` §3) carries these field names verbatim — `category`, `event`, `actor_type`, `actor_id`, `trace_id` — plus the chain columns `prev_hash`/`hash` (`bytea`).
- **PII minimization (normative — FR-142).** `details` and every other audit field MUST contain only pseudonymous references: entity UUIDs, enum values, `memberId`/`member_pseudo_id` — never names, email addresses, or free-text person PII. Member PII lives exclusively in the dedicated identity-mapping store (§7); FR-142 erasure hard-deletes or crypto-shreds that mapping, which pseudonymizes audit history without mutating a single hash-chained INSERT-only row — the chain remains intact and verifiable after erasure. Audit exports and object-storage archives inherit the rule, so they need no rewrite or crypto-shred on erasure either.
- **Tamper evidence via hash chaining.** Each audit record stores `prevHash` and `hash = SHA-256(prevHash || canonical(record))`, chained per tenant partition; a scheduled verifier job re-walks chains and emits `eip_job_failures_total{job="audit-verify"}` on mismatch; periodic chain-head anchors are written to object storage under WORM/object-lock where available. Audit tables accept only INSERT for application roles (no UPDATE/DELETE grants).
- **Chain write path.** Chaining is applied by an **asynchronous batch chainer**, not synchronously at insert — per-tenant hash-at-insert would serialize every tenant's audit writes through a single row lock, untenable at NFR-042 volumes. Rows INSERT with `prev_hash`/`hash` unset; a single-writer-per-tenant chainer job assigns both in strict `auditId` (UUIDv7) order per tenant, which is the per-tenant sequencing mechanism. Chain lag carries an SLO (default: 95% of rows chained within 60 s, all within 5 min), is exposed as a metric, and the verifier treats unchained rows older than the SLO bound as failures. Across monthly RANGE partitions the chain **continues unbroken**: the first row of a new partition links to the hash of the last row of the previous partition, and partition-boundary chain heads are included in the periodic object-storage anchors.
- **Retention and access.** Default 25 months, tenant-extendable, exportable (NDJSON) for SIEM shipping via the Loki/OTLP pipeline or file export. Readable by SECURITY_AUDITOR and TENANT_ADMIN (tenant scope) and PLATFORM_ADMIN (platform events); audit reads are themselves audited.

## 12. Compliance Mapping

| Control family (SOC 2 / ISO 27001) | Platform features |
|---|---|
| Access control (CC6.1–6.3 / A.5.15–A.5.18, A.8.2) | OIDC SSO, RBAC roles + permission catalog, tenant scoping + RLS, resource-level checks, session policy, service token lifecycle |
| Logical access — least privilege (CC6.1 / A.8.3) | Deny-by-default permissions, per-agent capability sandboxing, per-capability MCP RBAC |
| Encryption (CC6.7 / A.8.24) | TLS everywhere, AES-256-GCM envelope encryption, KMS SPI (Vault/HSM path), encrypted backups |
| Audit & monitoring (CC7.2–7.3 / A.8.15–A.8.16) | Hash-chained audit log, event taxonomy, SIEM export, alerting rules, trace correlation |
| Change management (CC8.1 / A.8.32) | Expand–contract migrations, signed images, versioned release manifests, upgrade gating |
| Vulnerability management (CC7.1 / A.8.8) | Dependency scanning gates, SBOM, base-image cadence, security testing checklist (§13) |
| System operations & availability (A1.1–A1.3 / A.8.14) | HA/DR with RPO/RTO targets, backup/restore runbooks, SLOs with error budgets |
| Data lifecycle & privacy (CC6.5, P-series / A.8.10–A.8.12) | Retention policies per data class, erasure workflow with signed completion, pseudonymization option, PII access gating |
| Supplier/third-party (CC9.2 / A.5.19–A.5.23) | LLM egress controls and per-provider policy, MCP allow-lists, connector credential scoping |
| Incident management (CC7.4–7.5 / A.5.24–A.5.27) | Incident response hooks (§14), audit evidence, on-call alert routing |

Compliance mapping is evidence support, not certification: the deploying organization owns the audit; EIP supplies the technical controls and their evidence trails.

## 13. Security Testing Checklist

- [ ] AuthN: JWT validation negative tests (expired, wrong audience/issuer, alg confusion, tampered signature); local-account lockout; break-glass audit.
- [ ] AuthZ: per-endpoint permission matrix tests generated from the permission catalog; resource-level ownership tests (cross-team, cross-org denial); RLS verified by attempting cross-tenant reads with a mis-scoped application session.
- [ ] Tenant isolation: automated cross-tenant probe suite across API, RAG retrieval, report artifacts, cache keys, Kafka consumer handling of mismatched envelope `tenantId`, and object-storage prefix isolation (attempted cross-prefix reads/writes through the storage service on `eip-ingest`/`eip-artifacts`, per ADR-018 — this periodic test is the stated mitigation for not using per-tenant MinIO credentials, §5).
- [ ] Secrets: no plaintext secrets in DB dumps, logs, traces, API responses, or heap dumps (sampled); rotation drill for master key and a connector secret; reveal-permission audit verified.
- [ ] AI: prompt-injection regression corpus (poisoned RAG documents, hostile MCP tool results) must not trigger out-of-manifest tool calls or policy-violating outputs; budget exhaustion behaves as specified; external-egress redaction verified.
- [ ] Web: OWASP ASVS L2 baseline — CSRF, XSS (report/dashboard rendering of ingested content), SSRF on connector URL config (deny link-local/metadata ranges, DNS-rebinding protection), injection (SQL/JSONB), file upload handling for Generic File/Document connector.
- [ ] Webhooks: signature bypass attempts, replay attempts (timestamp + nonce window), oversized payloads.
- [ ] Audit: hash-chain verifier detects seeded tampering; INSERT-only grants confirmed.
- [ ] Supply chain: unsigned image rejected by admission policy; SBOM matches image contents; CVE gate blocks a seeded critical.
- [ ] DoS: rate limits enforced per token/tenant; ingestion flood parks in DLQ without starving other tenants.
- [ ] Pen test before GA (Phase 5) and after major architecture changes; findings tracked as SecurityFinding entities with aging metrics.

## 14. Incident Response Hooks

- **Detection:** security-relevant alerts (auth failure spikes, break-glass use, audit-chain mismatch, anomalous secret access, agent budget exhaustion spikes, egress-policy drops) route through Prometheus Alertmanager to the enterprise on-call channel; audit stream is SIEM-exportable in near-real-time.
- **Containment switches (runbook-invocable, all audited):** disable a connector, revoke all service tokens for a tenant, disable external LLM providers platform-wide, disable MCP server exposure, force-logout a tenant's sessions, freeze a tenant (read-only mode).
- **Evidence:** hash-chained audit log + trace correlation gives a per-incident timeline (`traceId` joins audit, logs, traces); export bundle command produces a sealed evidence archive.
- **Recovery:** secret rotation procedure (§6), key rotation, restore-from-backup runbooks in `../operations/OperationsGuide.md`; post-incident review template records actions as DecisionRecord entities.

## 15. Acceptance Criteria

- [ ] Given a user with ENGINEERING_MANAGER on Team A only, when they request Team B delivery-risk detail via API or RAG query, then the response is 403/empty and an `access.denied` audit event is recorded.
- [ ] Given a tenant with pseudonymization enabled, when any dashboard, report, or RAG retrieval includes individual-grain data, then only `member_pseudo_id` values appear and de-mapping requires TENANT_ADMIN with audit.
- [ ] Given a poisoned document containing instruction-like text, when an agent retrieves it, then no out-of-manifest tool call occurs and the source is traceable in the agent run audit.
- [ ] Given an external LLM provider enabled with identity-egress restricted, when an agent prompt contains Member identity data, then the prompt is redacted before egress and the LLM call audit marks it external.
- [ ] Given any single audit row is modified in the database, when the chain verifier runs, then the tampering is detected and alerted within one verification cycle.
- [ ] Given a revoked service token, when it is presented, then the request fails and `auth.token.revoked` usage attempts are audited.
