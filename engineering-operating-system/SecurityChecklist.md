# Security Checklist

This checklist operationalizes [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) as gate **G3**. It is read by every implementation engineer (R-IE) before opening a PR, applied in full by the Security Architect (R-SA) on every **CC-2** PR, and re-used by the Release Manager (R-RM) and R-SA at release gate **RG2**. Section 2 is automated and runs on every PR; Section 3 is the manual review for CC-2 changes; Section 4 lists the release-time items. Terminology note ([./EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §0): "engineering agent" here means an agent building EIP — never one of the product's 18 runtime agents from [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md) §5.

## 1. Scope and verdicts

| Aspect | Rule |
|---|---|
| Gate | G3 — every PR (automated portion) + full checklist review for CC-2 (see [./QualityGatePolicy.md](./QualityGatePolicy.md)) |
| Owner | R-SA (A2 + A4). R-SA's verdict is blocking; override only by the human repository owner, recorded in the task file |
| CC-2 definition | Security-relevant change: authn/z, secrets, tenancy, audit, AI guardrails, crypto, PII ([./QualityGatePolicy.md](./QualityGatePolicy.md) §3) |
| Declaration | The PR MUST declare CC-2 in its change-class field. R-CR verifies the declaration; misclassification is a BLOCKER |
| Overlaps | RLS policy changes are also CC-1 (G4, R-CA + R-DBA). AI-guardrail changes are also CC-5 (eval suite + R-AIA, see [./TestingChecklist.md](./TestingChecklist.md) §9) |

## 2. Automated checks — every PR (G3 automated portion)

These run in CI (stage mapping in [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §14) and MUST be green before G8 review starts. No engineering agent may merge with a red G3 check.

| # | Check | Implementation | Source of record | On failure |
|---|---|---|---|---|
| A1 | Static analysis (SAST) | Checkstyle + ErrorProne (backend), ESLint + tsc (frontend) in the Static stage | TestingStrategy §14 | Fix; no waiver |
| A2 | Dependency scan | OWASP Dependency-Check + `pnpm audit` on PR; Trivy on built images | TestingStrategy §12 | Criticals block; highs require an R-SA-recorded waiver + DEBT entry |
| A3 | Secret scan | gitleaks over source, WireMock recordings, and simulation packs | TestingStrategy §12 | Fix; rotate any leaked credential immediately; no waiver |
| A4 | Isolation test suite (NFR-041) | RLS repository probes, API cross-tenant probes, RAG retrieval isolation, MinIO prefix isolation, Kafka envelope `tenantId` validation | TestingStrategy §3, §12; [../docs/product/PRD.md](../docs/product/PRD.md) NFR-041 | Fix; no waiver — isolation failures are never merged around |
| A5 | AuthZ matrix (fast subset) | Generated (role × permission-guarded endpoint) matrix; unmapped endpoints fail the build | TestingStrategy §12 | Fix; add the endpoint's permission mapping |
| A6 | License check | Dependency license allow-list (Apache-2.0/MIT/BSD/EPL; GPL-family prohibited in runtime) | [./DependencyManagement.md](./DependencyManagement.md) | Replace the dependency |

## 3. CC-2 manual review checklist

R-SA (or R-CR pre-screening for R-SA) MUST tick every applicable item. Items marked (BLOCKER) fail G3 outright.

### 3.1 Tenancy and data isolation

- [ ] Every new table carries `tenant_id uuid NOT NULL` and receives the standard RLS policy pair (`USING` + `WITH CHECK` on `current_setting('app.tenant_id')::uuid`) via the `R__rls_policies.sql` catalog — see [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §5. (BLOCKER)
- [ ] Tables without RLS are limited to the enumerated platform-scoped exceptions (DatabasePlan §2: `core.worker_heartbeat`, `quartz.qrtz_*`, `core.tenant`, Flyway history). Any new exception requires R-SA + R-DBA sign-off recorded in the PR.
- [ ] Tenant binding uses transaction-scoped `SET LOCAL app.tenant_id` only — never session-scoped, never string-concatenated (SecurityModel §4, DatabasePlan §12). Kafka consumers resolve tenant context from the envelope `tenantId` before any DB access ([../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §8).
- [ ] Changes to any RLS policy are declared CC-1 and routed through G4 (R-DBA + R-CA). (BLOCKER if undeclared)
- [ ] Redis keys use the `eip:{tenantId}:...` namespace; object-storage writes use the tenant bucket/prefix (SecurityModel §5).

### 3.2 Endpoints and authorization

- [ ] Every new `/api/v1` endpoint and MCP capability declares its required permission(s) from the authoritative catalog in `eip-tenancy` — deny-by-default; an endpoint with no declared permission MUST NOT exist (SecurityModel §4, FR-122). (BLOCKER)
- [ ] Service-layer permission checks repeat the controller-layer enforcement (defense in depth, SecurityModel §4).
- [ ] Resource-level scope checks exist where role checks are insufficient (e.g., manager of Team A must not read Team B detail — SecurityModel §4 enforcement layer 2), and the authz matrix tests cover the new cells.
- [ ] Denied requests emit `access.denied` audit events with `traceId` (SecurityModel §3 flow).

### 3.2b Ingress, webhooks, and outbound URLs

- [ ] New webhook intake endpoints validate the per-connector HMAC signature before enqueueing, reject stale timestamps/nonces (replay window), and enforce payload size limits (SecurityModel §2, §13; FR-012). Invalid signatures increment `eip_webhook_events_total{outcome="invalid_signature"}`.
- [ ] Operator-supplied URLs (connector endpoints, MCP servers, notification webhooks) pass SSRF defenses: deny link-local/metadata ranges, DNS-rebinding protection (SecurityModel §13 web item).
- [ ] Ingested content rendered in dashboards/reports is XSS-safe (SecurityModel §13 web item); file handling for the Generic File/Document connector is validated.

### 3.3 Secrets and credentials

- [ ] All new secrets flow through the platform secrets service (FR-113, FR-009): AES-256-GCM envelope encryption, DEK wrapped via the KMS SPI. Secrets MUST NOT appear in environment variables, config files, code, logs, traces, error messages, or API responses (SecurityModel §6). (BLOCKER)
- [ ] Secret fields are write-only in API/UI (OpenAPI `writeOnly`), masked in UI; `connector.secret.reveal` remains distinct, default-disabled, and audited.
- [ ] Every decrypt path emits a `secret.access` audit event with actor, purpose, and `traceId`.

### 3.4 Audit events

- [ ] Every new admin or security-relevant action emits an audit event in an existing category of the SecurityModel §11 taxonomy (`auth`, `access`, `admin`, `secrets`, `ai`, `mcp`, `data`, `platform`); new event names within a category are listed in the PR description; a new *category* requires R-SA approval and a SecurityModel docs PR first (docs-first law, [./EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §5).
- [ ] Audit records follow the §11 schema (UUIDv7 `auditId`, `tenantId`, actor, category, event, target, outcome, `traceId`, redacted details) and the table remains INSERT-only for application roles.
- [ ] NFR-042 coverage holds: 100% of admin actions, secret accesses, LLM calls, RAG retrievals, and MCP interactions audited.

### 3.5 PII and telemetry hygiene

- [ ] No Member names/emails in logs, metrics labels, or span attributes; identities appear only as ids (or pseudonyms) — [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md) §5. (BLOCKER)
- [ ] Span attributes include `eip.tenant_id` and never payload content, secrets, prompt text, or member identities (ObservabilityModel §4); metric labels stay cardinality-bounded (no user ids, no `entityId`, no free text — ObservabilityModel §1).
- [ ] Identity-mapping data stays access-restricted per SecurityModel §7; individual-grain signals remain gated behind `metric.individual.view` (default-disabled).

### 3.6 AI-specific items (CC-2 ∩ CC-5)

- [ ] Retrieved RAG chunks and MCP tool results are treated as untrusted: delimited data blocks, sanitization, no ability to alter tool allow-lists/budgets/system prompts — SecurityModel §8, [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md) §3.3, [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md) §2.4. (BLOCKER)
- [ ] LLM outputs pass schema validation, citation checks, numeric cross-checks, and policy filters before any side effect; invalid outputs fail auditable, never silently accepted (SecurityModel §8).
- [ ] Agent context is tenant-isolated: agents execute with the invoking user's effective permissions intersected with their static capability manifest — never a service-superuser context (SecurityModel §8, NFR-041). (BLOCKER)
- [ ] MCP remains deny-by-default: client servers TENANT_ADMIN-registered and allow-listed; server capabilities individually enabled with `mcp.capability.invoke:<capability>` RBAC (FR-104–FR-108, SecurityModel §9).
- [ ] Outbound-argument DLP: prompts/tool arguments leaving the deployment pass the redaction filter (identity pseudonymization, secret-pattern stripping) per the tenant egress policy, and the audit record marks the call external (SecurityModel §8).

### 3.7 Cryptography

- [ ] Only approved primitives: AES-256-GCM envelope encryption for stored secrets, Argon2id for local passwords, SHA-256 for token hashes and audit hash chaining, TLS ≥ 1.2 everywhere (SecurityModel §3, §6, §7, §11).
- [ ] No homegrown crypto: no custom algorithms, modes, paddings, nonce schemes, or random sources — JDK/vetted-provider implementations only. (BLOCKER)

### 3.8 Anti-surveillance guard (release-blocking)

- [ ] The change adds no individual-level metric surface: no per-person rankings, leaderboards, or raw activity counts in any API, dashboard, export, or agent output (FR-057, NFR-071). The static anti-surveillance guard test (TestingStrategy §12) still passes. (BLOCKER — this is the product's foundational anti-goal, guarded by R-PO)

## 4. Release gate RG2 (per release, owned by R-RM with R-SA A4 verdict)

- [ ] Security certification checklist — SecurityModel §13 — fully green on the release candidate, including AuthN/AuthZ negative tests, isolation probes, secrets hygiene, AI injection corpus, webhook, audit-chain, supply-chain, and DoS items.
- [ ] NFR-071 anti-surveillance review completed and recorded: no release may add individual-ranking capability; team-health metrics pass anti-toxic-ranking review.
- [ ] Pen-test-style review of all attack surface added in this phase (new endpoints, connectors, MCP capabilities, agents); full pen test before GA (Phase 5) and after major architecture changes (SecurityModel §13).
- [ ] Supply chain evidence current: signed images, SBOM per image, CVE gate green (SecurityModel §10).

## 5. Escalation

Security disputes follow the escalation chain in [./AgentResponsibilities.md](./AgentResponsibilities.md): R-SA holds an A4 blocking verdict on G3 and RG2; disagreement escalates R-IE/R-TE → owning domain architect → R-CA → human repository owner. An override of an R-SA block MUST be recorded as an escalation record in the task file.

## Related documents

- [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) — source of record for all controls above
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §12, §14 — security test suites and CI stages
- [../docs/product/PRD.md](../docs/product/PRD.md) — FR-057, FR-113, FR-122, FR-123, NFR-040/041/042/071
- [./QualityGatePolicy.md](./QualityGatePolicy.md) · [./CodeReviewChecklist.md](./CodeReviewChecklist.md) · [./TestingChecklist.md](./TestingChecklist.md) · [./ObservabilityRequirements.md](./ObservabilityRequirements.md)
- [./AIValidationWorkflow.md](./AIValidationWorkflow.md) · [./ReleaseManagement.md](./ReleaseManagement.md) · [./DependencyManagement.md](./DependencyManagement.md) · [./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md)
