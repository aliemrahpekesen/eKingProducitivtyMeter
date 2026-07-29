# Security Architecture Review

> Reviewer: Principal Security Architect · Date: 2026-07-06 · Verdict: **READY WITH CONDITIONS** — mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items.

## 1. Scope & reviewed documents

This review challenges the security architecture of the Engineering Intelligence Platform (EIP) against the committed NFR envelope (PRD §6: ≥10k repos, ≥1M WorkItems, ≥50 tenants, 100k events/h; ArchitectureOverview D3: ~5,000 active engineers) and against ~10× extreme-enterprise scenarios (10,000+ users, 500+ teams, 100+ integrations, air-gapped/private-CA/local-LLM-only environments). Multi-tenancy isolation is reviewed separately in [MultiTenancyReview.md](./MultiTenancyReview.md).

Primary sources (deep-read):

- [docs/architecture/SecurityModel.md](../../docs/architecture/SecurityModel.md) — threat model, AuthN/Z, secrets, audit, AI security, supply chain
- [docs/engineering/DatabasePlan.md](../../docs/engineering/DatabasePlan.md) — audit DDL, RLS, secrets table, retention
- [docs/product/PRD.md](../../docs/product/PRD.md) — FR-113/120–144, NFR-040/041/042/051/071
- [engineering-operating-system/SecurityChecklist.md](../../engineering-operating-system/SecurityChecklist.md) — gate G3 operationalization
- [docs/ai/AgentArchitecture.md](../../docs/ai/AgentArchitecture.md) — agent guardrails, budgets, LLM audit

Consulted: [docs/architecture/DeploymentModel.md](../../docs/architecture/DeploymentModel.md) (§7 air-gap, §10 egress, §12 TLS/private CA), [docs/operations/OperationsGuide.md](../../docs/operations/OperationsGuide.md) (§3.3 rotation, §12 security ops), [docs/ai/RAGArchitecture.md](../../docs/ai/RAGArchitecture.md), [docs/engineering/EventModel.md](../../docs/engineering/EventModel.md), [docs/testing/TestingStrategy.md](../../docs/testing/TestingStrategy.md), [CLAUDE.md](../../CLAUDE.md).

## 2. Extreme-scenario assessment

| Scenario | Holds / Degrades / Breaks | Reasoning (citations) | Mitigation path |
|---|---|---|---|
| RBAC at 10,000 users / 500 teams | **Degrades** | SecurityModel §3 flow resolves "TenantContext + effective permissions (roles → permission catalog)" plus resource-level team-scope checks (§4 layer 2) **per request**, with no documented caching or invalidation. At 10k users the role/scope resolution becomes a hot DB path on every API call (NFR-011 p95 < 300 ms at risk). The role model itself does not explode: 4 base roles + 6 seeded persona templates (§4) with scoped grants scale by grant rows, not by role count. | SEC-05: specify a permission/scope resolution cache with bounded staleness and event-driven invalidation on role change. No structural rework needed. |
| Service tokens at 100+ configured integrations | **Holds** | SecurityModel §3: tokens role-bound, permission-subset-capped, SHA-256-hashed, expiring (≤365 d), individually revocable, last-used tracked. Source-side connector credentials are covered by an explicit rotation routine with due-date tracking (OperationsGuide §12: "Connector credential rotation… ≤ 180 days recommended", §3.3 rotation dialog with `testConnection()`). | Solid. Minor: rotation-age alerting (vs. a column the operator must look at) would harden at 100+ connectors — folded into SEC-12 scope note. |
| Master-key compromise recovery | **Breaks (as documented)** | SecurityModel §6 rotation re-wraps DEKs "no data re-encryption needed", and OperationsGuide §12 routes "on suspicion of compromise" to that same §3.3 step 5 re-wrap. Cryptographically insufficient: an attacker holding the old master key + DB ciphertext already unwrapped the DEKs; re-wrapping unchanged DEKs protects nothing. Compromise recovery requires fresh DEKs (full re-encrypt) **and** rotation of the underlying connector credentials. | SEC-01 (MANDATORY): document a distinct compromise procedure before the Phase-0 secrets service is built, so the fresh-DEK re-encryption path is designed in. |
| Audit at NFR-042 100% coverage × 10k users | **Degrades → breaks at write path** | NFR-042 audits every admin action, secret access, LLM call, RAG retrieval, MCP interaction. SecurityModel §11 hash chain requires each record to reference `prevHash` per tenant — a per-tenant write serialization point at exactly the volumes where audit is busiest. Worse, the authoritative DDL (DatabasePlan §3 `audit.audit_event`) has **no hash/prevHash columns at all**, and the chain-continuation rule across monthly partitions (DatabasePlan §6) is undefined. Storage itself is fine (35 GB/3 y reference tenant, partitioned, archived). | SEC-02 (MANDATORY): reconcile schema + define the chain write path (sync vs. async chainer, partition boundary rule). SEC-08: incremental verification from WORM anchors so the verifier job cost doesn't grow with total history. SEC-09: state which audit trails are chained (`rag_retrieval_audit` and `llm_call` are separate tables — RAGArchitecture §13, AgentArchitecture §11). |
| Keycloak/IdP outage | **Degrades acceptably, under-specified** | DeploymentModel §9: "New logins fail; existing tokens valid until expiry; local break-glass admin available." Access tokens ≤ 15 min (SecurityModel §3) bound the damage window; clock skew ≤ 60 s is specified. But cached-JWKS TTL and behavior when JWKS refresh fails with a warm cache are not documented — an implementer must decide fail-open vs. fail-closed ad hoc. | SEC-07: document JWKS cache TTL and fail-closed rule. |
| Token revocation / force-logout | **Degrades** | SecurityModel §14 lists "force-logout a tenant's sessions" as a containment switch, but the architecture is stateless JWT validation (§3): without a denylist checked per request there is no mechanism to invalidate live access tokens before their ≤15-min expiry. Service tokens are fine (DB lookup per request → immediate revocation). | SEC-06: define the revocation mechanism (e.g., Redis denylist keyed on `sid`/`sub`, fail-closed semantics) or explicitly accept the ≤15-min window and reword §14. |
| Air-gap + private CA (incl. Playwright/E2E, OTel) | **Holds** | Best-documented area reviewed. DeploymentModel §7 (mirror-time cosign verification, offline model bundles, dependency-free runtime, default-deny egress), §12 (enterprise CA bundle mounted into all containers, JVM truststore built at startup, per-connector extra trust anchors, "validation is never disabled"), §10 (OTLP 4317/gRPC-TLS covered by the same mounted bundle). NFR-051 zero-egress. Remaining pinholes: Node-side tooling (Playwright E2E per TestingStrategy §10 runs against the TLS Compose stack; `NODE_EXTRA_CA_CERTS` guidance absent) and browser trust for the SPA (enterprise-managed, worth one sentence). | SEC-11: one-paragraph addition. Not structural. |
| Supply chain in air-gap | **Holds** | SecurityModel §10: cosign signing, admission verification (Kyverno/OpenShift), SBOM per image, model-bundle checksums, "air-gapped installs verify at mirror time" — the correct answer where in-cluster keyless verification is impossible. CVE watch for air-gapped installs ships scan reports inside the offline bundle (OperationsGuide §12). | None needed. Genuinely solid. |
| Local-LLM-only / no external providers | **Holds** | AgentArchitecture §13 defines full-platform behavior with zero LLM providers; egress policy is generated from configuration (DeploymentModel §10); external-provider redaction filter and egress classification per tenant (SecurityModel §8). | None needed. |
| HSM-less master key in air-gap | **Degrades (accepted)** | KMS SPI providers are env (demo), file, Vault (SecurityModel §6); "SPI allows enterprise HSM adapters later". The `file` provider's at-rest protection reduces to mounted-Secret + etcd/volume encryption — a deployer choice mentioned only generically (SecurityModel §7 "deployer's storage-class choice"). | SEC-10 (ACCEPTED-RISK): state the ceiling explicitly and recommend the Vault provider (Vault runs air-gapped) as the non-demo default. |

## 3. Strengths

1. **Threat-model discipline.** SecurityModel §1–§2 is a real STRIDE analysis with named assets, prioritization, and trust decisions that propagate into controls (TB4/TB5 untrusted-input stance drives §8 injection defenses). Rare quality at this stage.
2. **AI security is ahead of the industry baseline.** Static capability manifests enforced by the runtime regardless of model output, principal-intersection execution ("never a service-superuser context"), budgets as a security control, mandatory Validation Agent, `writeArtifact` as the sole mutating tool with staged writes and double sanitization (SecurityModel §8; AgentArchitecture §4 rules 1–6, §5.16). Shared-inference cache isolation (`tenantId`-partitioned KV/prefix caches, AgentArchitecture §7.1) addresses a threat most designs ignore.
3. **Air-gap and private CA are first-class, not bolted on.** DeploymentModel §7/§10/§12 cover mirroring with digests, offline model bundles, generated egress `NetworkPolicy`, mounted CA bundles, hot cert reload, and an explicit "no insecure-skip-verify switch" rule.
4. **Governance actually enforces the model.** SecurityChecklist G3 runs the isolation suite (NFR-041) on every PR with "no waiver" on failures, blocks unmapped endpoints via the generated authz matrix (A5), requires CC-2 declaration with misclassification as a BLOCKER, and ties RLS changes into G4 dual review. CLAUDE.md law 5/6 mirror the same constraints. The checklist cites concrete doc sections rather than platitudes.
5. **Secrets design is correct for routine operations.** Envelope encryption with AAD binding (`tenantId + secretId`), write-only API fields, distinct audited `reveal` permission, per-decrypt audit events (SecurityModel §6; DatabasePlan §3 `core.secret` with `kek_version`).
6. **Webhook and SSRF defenses are specified, testable, and gated** (SecurityModel §13; SecurityChecklist §3.2b), including DNS-rebinding and metadata-range denial on operator-supplied URLs.

## 4. Weaknesses

1. **Compromise-mode key recovery is wrong as written.** SecurityModel §6 "Rotation procedure" and OperationsGuide §12 conflate routine rotation (re-wrap) with compromise recovery. Re-wrapping DEKs after master-key compromise is a no-op against an attacker who exfiltrated ciphertext while holding the key. No procedure exists for fresh-DEK re-encryption or coordinated downstream credential rotation.
2. **Audit hash chain exists in prose, not in the schema.** SecurityModel §11 (prevHash/hash, per-tenant chains, INSERT-only) vs. DatabasePlan §3 `audit.audit_event` DDL (no hash columns; field names `action`/`actor_type` vs. §11's `category`/`event`/`actor{type,id}`). The write-path design (per-tenant serialization, chain across monthly partitions, sync vs. async) is undecided — and it is a Phase-0 table (FR-123).
3. **Erasure vs. immutability contradiction.** FR-142 / OperationsGuide §3.4 promise "audit-preserving redaction (personal identifiers are redacted)" in audit data, but SecurityModel §11 makes audit rows hash-chained and INSERT-only — redacting a chained row breaks the chain by design. Nothing states that audit `details` must never contain direct identifiers.
4. **Permission-resolution performance is unaddressed.** No caching/invalidation story for per-request effective-permission and team-scope computation (SecurityModel §3–§4) despite NFR-011 and the 10k-user stress case.
5. **Containment switches outrun the mechanism.** §14's "force-logout a tenant's sessions" has no supporting revocation design for stateless JWTs (§3).
6. **Verifier and SIEM guarantees are thin at scale.** Chain verification is described as "re-walks chains" (SecurityModel §11) — unbounded cost as history grows; SIEM export (NDJSON / Loki pipeline) has no stated delivery guarantee or gap-detection contract despite being incident evidence (§14).

## 5. Contradictions

| # | Side A | Side B | Impact |
|---|---|---|---|
| C1 | SecurityModel §11: audit records store `prevHash`/`hash`, schema fields `category`, `event`, `actor {type,id}` | DatabasePlan §3 `audit.audit_event` DDL: no hash columns; fields `action`, `actor_type`, `actor_id`; `traceparent` vs. §11 `traceId` | Implementer builds the table without tamper evidence, or invents the chain design ad hoc (SEC-02) |
| C2 | SecurityModel §7/§11: audit retention "25 months minimum" | DatabasePlan §10: "audit.audit_event — 24 months online; archive… before drop" | A tenant relying on the SecurityModel figure loses a month of online queryability (SEC-04) |
| C3 | OperationsGuide §12: master key rotation "on suspicion of compromise" → §3.3 step 5 (re-wrap only) | Cryptographic reality of envelope encryption (SecurityModel §6 design): re-wrap does not revoke access for a party that held the master key | Documented incident response is ineffective for the exact scenario it names (SEC-01) |
| C4 | SecurityModel §11 + SecurityChecklist §3.4: audit table INSERT-only, hash-chained | FR-142 / OperationsGuide §3.4: erasure applies "audit-preserving redaction" of personal identifiers | Redaction of chained rows is impossible without breaking tamper evidence (SEC-03) |
| C5 | SecurityModel §14 containment: "force-logout a tenant's sessions" | SecurityModel §3: stateless JWT validation, tokens valid ≤ 15 min, no denylist documented | Containment switch not implementable as specified (SEC-06) |

## 6. Missing decisions

An implementer will be forced to decide ad hoc:

1. **Audit chain write path** — synchronous hash-at-insert (per-tenant lock/sequence) vs. asynchronous chaining job; chain continuation across monthly partitions; behavior on chainer lag. (SEC-02)
2. **Which audit trails are hash-chained** — `audit.audit_event` only, or also `ai.llm_call_audit` and `rag_retrieval_audit` (RAGArchitecture §13 defines a separate table)? NFR-042's "audit log append-only" scope is ambiguous across the three stores. (SEC-09)
3. **Permission cache shape** — per-request vs. per-session effective-permission cache, TTL, invalidation on `user.role.assigned` events, and the revocation-propagation bound the platform commits to. (SEC-05)
4. **JWKS cache TTL / IdP-outage rule** — fail-closed after cache expiry? Warm-cache validity window? (SEC-07)
5. **JWT revocation mechanism** for the §14 force-logout switch. (SEC-06)
6. **Compromise-mode key rotation runbook** — fresh-DEK re-encryption sequencing, downstream credential rotation ordering, verification. (SEC-01)
7. **SIEM export contract** — at-least-once vs. best-effort, gap detection (chain-head comparison is the natural primitive). (SEC-12)

## 7. Risks

### Security
- **R1 (High).** Until SEC-01 lands, a real master-key compromise would be "recovered" with a procedure that leaves every connector credential effectively plaintext to the attacker. Connector credentials are asset A1 — the top of the platform's own priority list (SecurityModel §1).
- **R2 (High).** If `audit.audit_event` ships per the current DDL, tamper evidence (asset A6) silently does not exist; the §13 checklist item "hash-chain verifier detects seeded tampering" would fail or be stubbed.
- **R3 (Medium).** GDPR/works-council erasure requests (FR-142) arriving after GA could be unfulfillable against chained audit rows if identifiers leak into `details` (SEC-03).

### Scalability
- **R4 (Medium).** Per-tenant chain serialization + 100% audit coverage can throttle the hottest tenant's API/AI throughput at 10× scale; at the committed envelope it holds if the chain write is async or batched — which is exactly the undocumented decision (SEC-02).
- **R5 (Medium).** Uncached permission resolution puts NFR-011 at risk at 10k users; a cache is trivial now, a retrofit under load is not (SEC-05).

### Operational
- **R6 (Low-Medium).** Chain verification cost grows with history; without anchor-based incremental verification the job window eventually exceeds its schedule (SEC-08).
- **R7 (Low).** Private-CA friction in CI/E2E (Playwright/Node) generates "works on my machine" TLS failures in enterprise pilots (SEC-11).

### Implementation
- **R8 (Medium).** Five doc-vs-doc contradictions (§5) sit on Phase-0 components (audit, secrets); agentic implementers following CLAUDE.md law 1 ("docs-first") will faithfully implement whichever document they read first.

## 8. Required fixes

| ID | Severity | Target document(s) | Description | Status |
|---|---|---|---|---|
| SEC-01 | MANDATORY | SecurityModel §6; OperationsGuide §3.3, §12 | Add a **master-key compromise** procedure distinct from routine rotation: generate new master key, generate **fresh DEKs and re-encrypt every secret** (not just re-wrap), then rotate all downstream connector/webhook/LLM credentials, with verification counts and audit events. Fix OperationsGuide §12 row so "on suspicion of compromise" routes to this procedure, not to re-wrap-only §3.3 step 5. | APPLIED (2026-07-06) |
| SEC-02 | MANDATORY | DatabasePlan §3, §6; SecurityModel §11 | Reconcile the `audit.audit_event` DDL with SecurityModel §11: add `prev_hash bytea` / `hash bytea` (or explicitly move chaining to a separate ledger), align field names (`category`+`event` vs. `action`; `actor {type,id}`; `traceId`/`traceparent`). Specify the chain write path: per-tenant sequencing mechanism, sync-insert vs. async-chainer (recommend async batch chainer with a lag SLO to avoid write serialization), and the rule for chain continuation across monthly partitions. | APPLIED (2026-07-06) |
| SEC-03 | MANDATORY | SecurityModel §11; OperationsGuide §3.4 | Resolve erasure-vs-immutability: state normatively that audit `details` MUST contain only entity IDs / `member_pseudo_id` values, never names/emails/free-text PII, so FR-142 erasure never mutates chained rows (redaction happens at the identity-mapping layer, not in the audit store). Align the §3.4 "audit-preserving redaction" wording with this mechanism. | APPLIED (2026-07-06) |
| SEC-04 | RECOMMENDED | SecurityModel §7/§11 or DatabasePlan §10 | Reconcile audit retention: pick one figure ("25 months minimum" vs. "24 months online + archive") and make the other document reference it, per the §7 single-source-of-record convention. | SCHEDULED (pre-Sprint-3) |
| SEC-05 | RECOMMENDED | SecurityModel §4; docs/engineering/BackendPlan.md (tenancy section) | Specify effective-permission/scope caching: cache key (principal, tenant), bounded TTL (≤ 60 s) **plus** event-driven invalidation on `user.role.assigned` / token revocation, and state the committed revocation-propagation bound. Add a load-test line for permission resolution at 10k users to TestingStrategy performance targets. | SCHEDULED (pre-Sprint-3) |
| SEC-06 | RECOMMENDED | SecurityModel §3, §14 | Define the force-logout/token-revocation mechanism for stateless JWTs (e.g., Redis-backed denylist on `sid`/`sub` checked per request, fail-closed if Redis is down for denylisted-tenant checks) — or explicitly downgrade §14's claim to "new tokens blocked; live tokens expire ≤ 15 min" and accept the window. | SCHEDULED (pre-Sprint-3) |
| SEC-07 | RECOMMENDED | SecurityModel §3 | Document JWKS caching: refresh interval, cache TTL, and the rule that token validation fails closed when signature keys cannot be verified; state the warm-cache validity window during IdP outage (currently only implied by DeploymentModel §9). | SCHEDULED (pre-Sprint-3) |
| SEC-08 | RECOMMENDED | SecurityModel §11 | Specify **incremental** chain verification: verifier re-walks only from the last WORM-anchored chain head, with a full re-walk on demand; state expected verifier cost per tenant per cycle so ops can size the job at 50+ tenants. | SCHEDULED (pre-Sprint-3) |
| SEC-09 | RECOMMENDED | SecurityModel §11; RAGArchitecture §13; AgentArchitecture §11 | State explicitly which NFR-042 trails are hash-chained: `audit.audit_event` only, with `ai.llm_call_audit` and `rag_retrieval_audit` as INSERT-only satellite trails referenced from chained summary events — or chain all three. Today the three documents are silent on the relationship. | SCHEDULED (pre-Sprint-3) |
| SEC-10 | ACCEPTED-RISK | SecurityModel §6; DeploymentModel §11 | Document the air-gap KMS ceiling: `file` master-key protection at rest equals mounted-Secret + etcd/volume encryption (deployer-owned); Vault provider is the recommended non-demo default (Vault operates air-gapped); HSM support arrives only via a future KMS SPI adapter. Revisit trigger: first customer with a hard HSM/FIPS mandate. | ACCEPTED (MVP) |
| SEC-11 | RECOMMENDED | DeploymentModel §12; TestingStrategy §10 | Complete the private-CA chain for non-JVM components: Node tooling (`NODE_EXTRA_CA_CERTS` for Playwright/pnpm in TLS environments), a note that SPA/browser trust is enterprise-desktop-managed, and an explicit statement that OTel exporters use the same mounted bundle. | SCHEDULED (pre-Sprint-3) |
| SEC-12 | RECOMMENDED | SecurityModel §11, §14 | Define the SIEM export contract: delivery semantics (at-least-once with cursor/checkpoint), gap detection via chain-head comparison, and completeness verification guidance for the deploying org's auditors. | SCHEDULED (pre-Sprint-3) |

## 9. Area verdict & conditions

**READY WITH CONDITIONS.** Mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items.

The security architecture is fundamentally sound — threat-model-driven, defense-in-depth on tenancy, best-in-class AI-pipeline security, and a genuinely credible air-gap story, with governance (G3/SecurityChecklist) that enforces rather than decorates. The blocking issues are all documentation-consistency and missing-decision defects concentrated on Phase-0 components (audit, secrets), which is exactly where they must be fixed before implementation starts.

**Conditions gating readiness (fix in source docs before any Phase-0 implementation task):**

- **SEC-01** — correct master-key compromise recovery procedure
- **SEC-02** — audit schema/chain reconciliation + chain write-path decision
- **SEC-03** — erasure-vs-immutability resolution (no direct PII in chained audit rows)

SEC-04–SEC-09, SEC-11, SEC-12 are due by Sprint 3 / next design iteration. SEC-10 is accepted for MVP with the stated trigger.

## 10. Inviolable principles

1. **Deny-by-default everywhere:** no `/api/v1` endpoint or MCP capability exists without a declared permission; no wildcard tool adoption; no implicit "system reads everything" principal (SecurityModel §4, §9; RAGArchitecture §8).
2. **Secrets are never plaintext at rest, in logs, in traces, in API responses, or in env vars** — envelope encryption via the KMS SPI is the only storage path (SecurityModel §6; SecurityChecklist §3.3 BLOCKER).
3. **Agents execute with the initiating principal's permissions intersected with a static capability manifest** — model output can never widen tool access, budgets, or scope (SecurityModel §8; AgentArchitecture §4 rule 1).
4. **All retrieved/tool-returned/LLM-generated content is untrusted input** — sanitized, delimited, validated before side effects (SecurityModel §8; RAGArchitecture §3.3).
5. **Audit is append-only and tamper-evident; audited categories per NFR-042 are 100%-coverage, no sampling** — and (post-SEC-03) chained audit rows never contain direct personal identifiers.
6. **TLS validation is never disabled** — additional trust anchors only; no insecure-skip-verify switch may ever be introduced (DeploymentModel §12).
7. **Air-gap purity:** zero telemetry, license phone-home, or runtime downloads; egress is generated exclusively from tenant configuration (NFR-051; DeploymentModel §10).
8. **No individual-surveillance capability ships, ever** — release-blocking (FR-057, NFR-071; SecurityChecklist §3.8).
