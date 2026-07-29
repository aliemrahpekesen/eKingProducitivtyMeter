# MCP Architecture Review — Architecture Readiness Board

> Reviewer: Principal AI Architect (RAG & MCP security) · Date: 2026-07-06 · Verdict: **READY WITH CONDITIONS** — mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items.

## 1. Scope & reviewed documents

Primary subject: [docs/ai/MCPArchitecture.md](../../docs/ai/MCPArchitecture.md) (all sections). Reviewed against:

- [docs/architecture/SecurityModel.md](../../docs/architecture/SecurityModel.md) — §3 (service tokens), §5, §8, §9 (MCP security), §11 (audit)
- [docs/product/PRD.md](../../docs/product/PRD.md) — FR-104–FR-108, NFR-041, NFR-042, NFR-051
- [docs/ai/AgentArchitecture.md](../../docs/ai/AgentArchitecture.md) — §4 (ToolRegistry), §6.2 (Validation), §13 (degradation)
- [docs/ai/RAGArchitecture.md](../../docs/ai/RAGArchitecture.md) — §3.3, §8 (symmetric defenses; token-principal retrieval)
- [docs/architecture/DeploymentModel.md](../../docs/architecture/DeploymentModel.md) — §10/§12 (egress, air-gap)
- Governance: [CLAUDE.md](../../CLAUDE.md), [engineering-operating-system/SecurityChecklist.md](../../engineering-operating-system/SecurityChecklist.md), [engineering-operating-system/QualityGatePolicy.md](../../engineering-operating-system/QualityGatePolicy.md), [engineering-operating-system/TestingChecklist.md](../../engineering-operating-system/TestingChecklist.md)

Calibration: committed envelope per PRD §6; extreme scenarios ~10× (100+ integrations, 10k+ users, 500+ tenants-equivalent load, air-gapped/private-CA environments).

## 2. Extreme-scenario assessment

| Scenario | Holds / Degrades / Breaks | Reasoning (doc + section) | Mitigation path |
|---|---|---|---|
| 100+ registered external MCP servers per deployment | **Holds** | Per-server connection pools, circuit breakers, health probes, rate limits (MCPArchitecture §2.1, §6); probe fan-out at 100 servers is trivial load. Servers surface on the Connector Health Monitor like any connector (§6). | None needed; per-tenant server-count quota worth stating for hygiene. |
| 10,000+ users driving agent runs that call MCP tools | **Holds / degrades** | Client-role calls execute inside agent runs, already budgeted and quota-capped (SecurityModel §8 budgets); per-tenant Redis rate limits (§2.1). Degradation is explicit (§8: fail closed on auth, fail fast on infra). | Scale `eip-workers`; already the platform's horizontal axis. |
| High-volume external clients on the MCP server (10× NFR-003-equivalent call rate) | **Degrades** | Endpoint scales with `eip-app` replicas (§6 topology). Every call writes an audit row (§3.1) into the platform's per-tenant hash-chained audit log (SecurityModel §11) — sequential chaining per tenant partition is a write-serialization point at 10× call rates. | Batched/segmented chain anchoring; per-token rate limits already bound worst case. Raise as platform audit-throughput question (open question 5). |
| Multi-user enterprise assistant behind one service token | **Breaks (confidentiality within tenant)** | §3.1: token = one RBAC principal; `eip.retrieve_citations` returns whatever the *token* may read (§3.2, RAGArchitecture §8 service-principal note). An assistant serving 5,000 employees through one token leaks any token-readable content to every one of them — the confused-deputy pattern. No on-behalf-of semantics exist, and the risk is nowhere documented. | MCP-01: document the delegation stance, per-audience token guidance, admin warnings on broad-grant tokens. OBO (e.g., RFC 8693-style exchange) is a non-foreclosed later addition since tokens already map to principals. |
| Fully air-gapped / restricted egress / private CA | **Holds** | Streamable HTTP with mandatory TLS + per-server CA bundles (§2.3); "no MCP feature requires external egress"; in-perimeter by construction (§6); egress NetworkPolicy generated from configuration (DeploymentModel §10, SecurityModel §10). Genuinely solid. | Tighten "should mirror" language (MCP-06); stdio caveat (MCP-05). |
| Hostile registered MCP server (malicious results, rug-pull, drift) | **Degrades gracefully** | Result schema validation + size caps (§2.2), injection screen with redaction (§2.4), drift-suspends-allow-listing (§2.4, §6), no chaining into mutation (§2.4), outbound DLP (§2.4). Residual: screening evasion classes shared with RAG (encoding/homoglyph/fragmented instructions — see RAG review RAG-04). | MCP-07 (shared screening engine + corpus). Containment via capability sandbox holds regardless. |
| Malicious stdio server binary in worker image | **Degrades → potential lateral movement** | §2.3 limits stdio to deployment-config commands with resource limits — but the child process inherits the worker pod's network identity, and worker pods legitimately hold connector/LLM/MCP egress (§6 topology; DeploymentModel §10). Gateway-level origin checks do not constrain a child process's own sockets. | MCP-05: no-network default for stdio children (or dedicated sandboxed pod), seccomp profile, documented threat statement. |
| 500+ tenants × capability configs × token fleets | **Holds** | Config per tenant, JSON-Schema validated, versioned/audited (§4); effective-access view computes the same intersection the runtime enforces (§4) — excellent operability at fleet scale. | None needed. |

## 3. Strengths

1. **Single choke point (`McpClientGateway`), no agent holds a raw connection** (§2.1) — the correct topology; every control (schema, size, screening, audit, breaker) has exactly one place to live.
2. **Rug-pull defense** (§2.4): description/schema drift suspends allow-listing pending re-approval, with `tools/list_changed` used to trigger immediate drift checks (§2.3). Ahead of most MCP deployments.
3. **Outbound exfiltration controls** (§2.4): argument minimization to declared schema, outbound DLP with block-on-secret, content-class audit. This direction is usually forgotten entirely; here it has an acceptance criterion (§10).
4. **Deny-by-default is structural in both roles**: candidates-not-usable-until-allow-listed (client, §2.1); per-capability flags default off with per-tenant enablement (server, §3.1); governance backs it (SecurityChecklist items 42 and 76 — permission declaration is a BLOCKER).
5. **`schemaVersion` policy** (§3.2) is adequate and unusually complete: semver, audit stamping per call, dual-MAJOR deprecation window (≥90 days), deprecated-usage metrics, admin visibility of lagging tokens, structured version-retired errors. Only the version *selection mechanism* is missing (MCP-04).
6. **`spawnedRunId` linkage** (§3.1) makes an externally triggered report traceable MCP call → agent run → LLM calls — matches NFR-042's spirit end-to-end, with an acceptance criterion (§10).
7. **Mock MCP server with scripted adversarial scenarios** (§9) — the scenario file *is* the specification; injection, drift, oversized, egress-violation fixtures are all named, and the security regression suite requires a fixture per new defense.
8. **Anti-ranking stance carried through the protocol boundary** (§3.2 design note: caveats always included) — an easy thing to drop at an API boundary, deliberately kept.
9. **Explicit scope restraint**: external resources/prompts primitives recorded but not consumed until they get the same defense treatment (§2.1 scope note).

## 4. Weaknesses

1. **No delegation model for the server role.** §3.1's token-per-principal design is clean but silent on the dominant real-world consumer: a multi-user assistant. Without documented on-behalf-of semantics or per-audience token guidance, `eip.retrieve_citations` becomes an intra-tenant permission bypass in exactly the deployment pattern MCP exists to serve (see scenario table). The least-privilege warning (§5) is not enough — nothing tells an admin that one token shared by many humans collapses all their permission differences.
2. **Audit coverage gap vs NFR-042.** §3.1 audits `tools/call`; the taxonomy (§5) covers calls, discovery, config, tokens, auth failures. But the server exposes **resources** (`eip://runs/{runId}`, `eip://reports/{reportId}`, §3.2) — resource reads are MCP interactions containing report content, and no audit event is specified for them. `tools/list` (capability enumeration) is likewise unaudited. NFR-042 requires 100% of MCP interactions audited.
3. **Version selection mechanism undefined.** §3.2 says old and new MAJOR are "served alongside" — but not *how* a client addresses one: distinct tool names (`eip.query_metrics_v2`)? a version field? MCP has no standard content negotiation for this; an implementer will invent it ad hoc, and it becomes wire contract immediately.
4. **stdio transport weakens the egress story** (§2.3): child processes are resource-limited but not network-restricted; the gateway's origin allow-list governs the gateway's own HTTP calls, not sockets a stdio server opens itself from inside the worker pod.
5. **Egress mirroring is "should".** §5: deployment egress policy "should mirror the registered set". DeploymentModel §10 states egress is generated from configuration, and an acceptance criterion exists there — but MCPArchitecture itself leaves it advisory, and no criterion asserts a *network-level* block of an unregistered origin (the §9 fixture "egress to unregistered origin" tests the gateway layer).
6. **Embedded-resource fetching underspecified** (§2.2 item 3): origin-match is required, but redirect handling (registered origin 302 → attacker origin), per-fetch size caps, and content-type verification for the fetched resource are not stated. SSRF defenses in SecurityChecklist item 50 cover operator-supplied URLs at registration, not server-supplied resource URIs at runtime.
7. **Screening engine duplication risk.** §2.4 and RAGArchitecture §3.3 describe the same defense with different details (MCP redacts high-confidence hits; RAG flags-and-serves). The asymmetry is defensible (a wiki page about injection is legitimate content; an ITSM tool result containing "ignore previous instructions" is not), but nothing states the rationale or that the underlying detector/corpus is shared — two drifting implementations of the platform's most adversarial control is a predictable failure mode.
8. **Token-hashing spec conflict** (see contradictions C2) and **capability naming conflict** (C1) mean an implementer touching `eip-tenancy` and `eip-ai` reads two different specs for the same objects.

## 5. Contradictions

| # | Side A | Side B | Impact |
|---|---|---|---|
| C1 | MCPArchitecture §3.2: capabilities `eip.query_metrics`, `eip.query_work_items`, `eip.retrieve_citations`, `eip.list_generated_reports`, `eip.trigger_report_generation` | SecurityModel §9: "e.g. `metrics.query`, `report.fetch`, `workitem.search`" | Permission catalog entries `mcp.capability.invoke:<capability>` (SecurityModel §4) need one canonical capability naming; today two exist. |
| C2 | MCPArchitecture §3.3: MCP service tokens "stored hashed (Argon2id)" | SecurityModel §3: service tokens "stored as SHA-256 hash, prefix-identifiable (`eipt_...`)" | Either MCP tokens are a second token system (unstated) or the same system with two specs. Argon2id vs SHA-256 is a real implementation fork (KDF cost vs lookup latency on every call). |
| C3 | MCPArchitecture §3.1: server-role authentication is service tokens (only) | SecurityModel §9: capability RBAC evaluated against "the authenticated caller's tenant-scoped identity (**service token or OIDC**)" | Whether human-OIDC principals may call `/api/v1/mcp` changes the auth middleware, the audit actor model, and the delegation analysis (MCP-01). |
| C4 | MCPArchitecture §3.3: token expiry "≤ 1 year" | SecurityModel §3: service tokens "expiring (default 90 days, max 365)" | Compatible on max, but MCP omits the 90-day default — implementers of the MCP token UI will default to 1 year. Minor; fold into MCP-03. |

## 6. Missing decisions

1. Delegation stance for the server role: token-as-principal only (v1), per-audience token guidance, whether OBO is roadmap or rejected (MCP-01).
2. Audit events + retention class for MCP resource reads and `tools/list` (MCP-02).
3. Capability MAJOR version addressing mechanism on the wire (MCP-04).
4. stdio child-process sandbox: network access, seccomp, filesystem scope (MCP-05).
5. Whether NetworkPolicy generation from the registered-server set is mandatory and drift-checked, or advisory (MCP-06).
6. Shared vs separate injection-screening implementation and corpus across RAG and MCP; documented rationale for the redact-vs-flag asymmetry (MCP-07).
7. Redirect/content-type/size policy for embedded-resource fetches (MCP-08).
8. Canonical token system: are MCP service tokens the SecurityModel §3 tokens? One hashing/expiry/prefix spec (MCP-03).
9. Whether an absent capability entry in the tenant config JSON (§4) is equivalent to `enabled: false` (implied, never stated — one sentence closes it; folded into MCP-02's audit/spec pass).

## 7. Risks

**Security.** The confused-deputy pattern (weakness 1) is the highest-consequence item: it requires no attacker, only a normal enterprise deployment. Injection-screen evasion classes are shared with RAG; containment (no chaining into mutation, Validation Agent, budgets) is strong and symmetric, so residual risk is narrative manipulation of one run's output — bounded, but flag-based telemetry will under-report (see RAG review RAG-04). stdio lateral movement (weakness 4) matters most in air-gapped installs where the perimeter *is* the control.

**Tenant isolation.** Server role: token → single tenant binding + RLS context (§3.1) with cross-tenant attempts required to 401/403-and-audit (§9 tests) — solid, two layers. Client role: per-tenant server registrations and credentials; no cross-tenant sharing surface identified. Genuinely solid; no finding.

**Scalability.** No structural ceilings at 10× in either role (see scenario table); audit hash-chain write serialization is the only flagged pressure point and is platform-wide, not MCP-specific.

**Operational.** Version retirement (§3.2) will strand slow-moving enterprise clients; the admin visibility of lagging tokens is the right mitigation — recommend the retirement action itself require explicit admin acknowledgment when deprecated-call counts are nonzero (fold into MCP-04). Circuit-breaker + health surfacing on the Connector Health Monitor reuses existing ops muscle — good.

**Data consistency.** `eip.trigger_report_generation` supports idempotency keys and fails fast with no partial run records on Kafka unavailability (§8) — correct.

**Implementation.** C1–C3 will surface as merge-time conflicts in `eip-tenancy` (permission catalog is a contract anchor, CC-1, requiring G4 review per QualityGatePolicy) — cheap to fix now, expensive after the catalog ships.

**Governance.** Strong: SecurityChecklist items 42 (permission declaration, BLOCKER), 50 (SSRF on MCP URLs), 63 (NFR-042), 73 (untrusted treatment, BLOCKER), 76 (deny-by-default); TestingChecklist ties isolation-suite extension to retrieval/tenancy changes. Gaps: no checklist line for the outbound DLP screen or for deprecation-window conformance; neither blocks readiness (the §9/§10 test spec covers DLP), but SecurityChecklist should absorb them (folded into MCP-02/MCP-04 target lists).

## 8. Required fixes

| ID | Severity | Target document(s) | Description | Status |
|---|---|---|---|---|
| MCP-01 | MANDATORY | MCPArchitecture §3.1/§3.3/§5; SecurityModel §9 | Document the delegation model before implementation: (a) state explicitly that the service token's principal is the effective principal for every capability — there is no on-behalf-of in v1; (b) name the confused-deputy risk: a token shared by a multi-user consumer grants every downstream user the union of the token's read scope, including `eip.retrieve_citations` ACL grants; (c) require deployment guidance + admin-UI warning for tokens whose grant scope exceeds a configured breadth threshold, and recommend per-audience tokens; (d) state whether OBO/token-exchange is roadmap or rejected, so the token schema does not foreclose it. | APPLIED (2026-07-06) |
| MCP-02 | MANDATORY | MCPArchitecture §3.1/§3.2/§5; SecurityModel §11 | Close the NFR-042 audit gap: specify audit events for MCP **resource reads** (`mcp.server.resource_read` with resource URI, principal, outcome) and `tools/list` enumeration; add both to the SecurityModel §11 taxonomy and an acceptance criterion in §10. Also state explicitly that a capability absent from tenant config is disabled. | APPLIED (2026-07-06) |
| MCP-03 | MANDATORY | MCPArchitecture §3.1/§3.3; SecurityModel §3/§9 | Reconcile the auth specs: (a) one canonical capability naming — update SecurityModel §9 examples to the `eip.*` names of §3.2; (b) one token system — state that MCP service tokens are the SecurityModel §3 platform service tokens and unify the hash algorithm (pick one: Argon2id or SHA-256 — for high-QPS bearer lookup, SHA-256 with high-entropy random secrets is the defensible choice; say so) and the expiry default (90 days); (c) resolve whether OIDC principals may call `/api/v1/mcp` (SecurityModel §9 says yes, MCPArchitecture says tokens only) — pick one for v1 and align both documents. | APPLIED (2026-07-06) |
| MCP-04 | RECOMMENDED | MCPArchitecture §3.2 | Define the capability version addressing mechanism: recommended — versioned tool names (`eip.query_metrics` = current MAJOR alias, `eip.query_metrics.v1`/`.v2` explicit) published in `tools/list` with `schemaVersion` metadata; deprecation notices in result `_meta`. Additionally: retiring a MAJOR with nonzero deprecated-call counts in the trailing 30 days requires explicit admin acknowledgment. Add a SecurityChecklist/release line for deprecation-window conformance. | SCHEDULED (pre-Sprint-3) |
| MCP-05 | RECOMMENDED | MCPArchitecture §2.3; SecurityModel §10; DeploymentModel §10 | Specify stdio child-process sandboxing: default-deny network for stdio MCP server children (dedicated NetworkPolicy-isolated sidecar/pod, or namespace/no-net execution), seccomp `RuntimeDefault`, read-only filesystem, and an explicit threat statement that a stdio server binary is trusted code reviewed at deployment time — not tenant-configurable. | SCHEDULED (pre-Sprint-3) |
| MCP-06 | RECOMMENDED | MCPArchitecture §5/§10; DeploymentModel §10 | Upgrade egress mirroring from "should" to MUST for Kubernetes deployments: NetworkPolicy egress for worker pods is generated from the registered MCP server set, regenerated on registration changes, with a drift check alert. Add an acceptance criterion: a gateway-bypassing connection attempt from a worker pod to an unregistered origin is blocked at the network layer (not merely refused by the gateway). | SCHEDULED (pre-Sprint-3) |
| MCP-07 | RECOMMENDED | MCPArchitecture §2.4; RAGArchitecture §3.3; SecurityModel §8 | State that RAG and MCP injection screening share one detection engine and one adversarial fixture corpus (single implementation in `eip-ai`, one tuning surface, metrics `eip.rag.injection_flags` / `eip.mcp.client.injection_flags` from the same detector versions); document the intentional response asymmetry (MCP: redact high-confidence hits; RAG: flag-and-serve) and its rationale. Apply the same normalization hardening as RAG review fix RAG-04. | SCHEDULED (pre-Sprint-3) |
| MCP-08 | RECOMMENDED | MCPArchitecture §2.2 | Specify embedded-resource fetch policy: no redirect following across origins (redirects only within the registered origin, depth-limited), per-fetch size cap (reuse `maxResultBytes`), content-type verification against declaration, fetch timeout within the tool-call budget, and fetches audited as part of the parent call. | SCHEDULED (pre-Sprint-3) |
| MCP-09 | ACCEPTED-RISK | MCPArchitecture §2.4 | Semantic/data-level manipulation by a registered external server that passes schema, size, and screening (plausible-but-false data): accepted for MVP — containment is citation-to-source attribution, Validation Agent checks, and the admin's trust decision at registration. Revisit trigger: any incident of manipulated tool data reaching a published report, or adoption of external resources/prompts primitives. | ACCEPTED (MVP) |

## 9. Area verdict & conditions

**READY WITH CONDITIONS.** Mandatory conditions were applied in the 2026-07-06 fix wave; remaining conditions are the RECOMMENDED items. This is a strong document — deny-by-default is structural in both directions, the client-side trust controls (rug-pull suspension, outbound DLP, schema validation, single gateway choke point) exceed current industry practice, the versioning policy is complete but for its wire mechanism, and the air-gap posture is coherent. The gaps are concentrated on the server role's identity model: an undocumented delegation stance that breaks confidentiality in the most likely enterprise consumption pattern, an NFR-042 audit gap on resources, and three spec conflicts with the SecurityModel that sit on a contract anchor (the permission catalog).

Conditions gating readiness (must land in source docs before Phase 4 MCP tasks are cut — and MCP-03's catalog naming before the `eip-tenancy` permission catalog ships in earlier phases): **MCP-01, MCP-02, MCP-03**.
RECOMMENDED fixes MCP-04–MCP-08 due before Sprint 3 / next design iteration; MCP-09 accepted with stated revisit trigger.

## 10. Inviolable principles

1. Deny-by-default in both directions: no external tool reaches an agent without explicit allow-listing + RBAC mapping; no internal capability is exposed without an explicit per-tenant flag.
2. Every MCP interaction — calls, discovery, resource reads, list operations, auth failures, config and token lifecycle — is audited, tenant-scoped, immutable (NFR-042: 100%, no sampling).
3. External MCP tool results are untrusted data: provenance-labeled, schema-validated, size-capped, screened; they can never alter allow-lists, budgets, or system prompts, and can never chain directly into a mutating action.
4. The authenticated principal (token-bound, single-tenant) is the effective principal for every capability; no capability ever executes with more privilege than that principal, and no cross-tenant token exists.
5. A previously approved external tool whose description or schema changes is suspended until a human re-approves (rug-pull rule) — no silent re-acceptance.
6. Outbound arguments to external servers are schema-minimized and DLP-screened; a detected secret blocks the call before any network dispatch.
7. The gateway connects only to registered origins, and deployment egress policy is derived from the registered set; no MCP feature introduces mandatory external egress (air-gap invariant).
8. MCP is a thin protocol adapter over the same DTOs, RBAC, RLS, and error semantics as REST — never a second, weaker API (FR-108).
9. Breaking capability changes ship as a new MAJOR served alongside the old through the deprecation window; retirement is announced, metered, and never silent.
10. Fail closed on authorization, fail fast on infrastructure, degrade explicitly in agent outputs — never fabricate in place of a failed MCP call.
