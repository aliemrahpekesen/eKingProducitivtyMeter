# Module: eip-ai

- **Root package:** `com.eip.ai`
- **Owner:** R-AIA
- **Allowed dependencies:** eip-core, eip-tenancy, eip-analytics
- **State:** M6-A landed (ADR-024): the opt-in, per-tenant AI explanation layer. The fuller
  agent-orchestration/RAG/MCP roadmap below is still Phase-0 skeleton.

## Purpose

Agent orchestration (LangChain4j), LLM provider SPI, per-tenant/per-agent routing, budgets, guardrails, LLM-call audit, RAG pipeline, MCP client and server.

M6-A ships the first slice of this: a thin, deterministic-first AI explanation layer (ADR-024) that
only explains already-computed, team-level dashboard/report metrics — it never computes a metric
itself, never sees individual-level data (none exists in the canonical model, NFR-071), and every
narrative is numerically cross-checked against the deterministic data it was given before being
returned. Off by default per tenant.

- `com.eip.ai.spi` — the pure `LlmClient`/`LlmRequest`/`LlmResponse` provider contract (LLM SPI
  0.1, NFR-060 semver).
- `com.eip.ai.providers` — `AiHttp` (minimal JDK HttpClient helper), the Ollama and
  OpenAI-compatible `LlmClient` implementations, and `LlmClientFactory`.
- `com.eip.ai.api` — the named-interface ports (`ManageAiPolicyUseCase`, `GetAiStatusQuery`,
  `ExplainInsightsUseCase`, `NarrateReportUseCase`), their DTOs, and the unchecked failure modes.
- `com.eip.ai.application` — `AiPolicyService`, the pure `PromptComposer`/`NumericCrossChecker`,
  and `ExplainService` (orchestration).
- `com.eip.ai.persistence` — `TenantAiPolicyRepository`, `LlmCallAuditRepository`.

## Owned tables / topics / endpoints

- `core.tenant_ai_policy` (V7 migration) — one row per tenant: enabled flag, provider/baseUrl/model, secret reference, temperature/maxTokens.
- `ai.llm_call_audit` (V7 migration) — hash-only call ledger (prompt/response SHA-256 + char counts, never content); one row per `explain`/`narrate` attempt regardless of outcome.
- `GET`/`PUT /api/v1/ai/policy`, `GET /api/v1/ai/status`, `POST /api/v1/insights/explain`, `POST /api/v1/reports/{id}/narrative` — wired in `eip-app` (`AiPolicyController`, `AiExplainController`); this module owns the ports and DTOs those controllers call.

Ownership is declared per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md) (tables) and [EventModel §3](../../docs/engineering/EventModel.md) (topics) as further content lands; this charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)). In particular, this module does NOT depend on `eip-reports` — see [ADR-024](../../docs/adr/ADR-024-ai-explanation-layer.md) for why `NarrateReportUseCase` takes the report's content directly instead of a `reportId`.
- Package-by-module: all code sits under `com.eip.ai.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).
- No external egress unless the tenant explicitly configures a provider `baseUrl` (ADR-024); nothing in `com.eip.ai.providers` calls out on its own.
- Every generated narrative passes `NumericCrossChecker` or is rejected — never returned unverified.

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md). The LangChain4j-backed agent runtime, RAG pipeline, and MCP client/server remain future phases (ADR-024); [DEBT-021](../../work/debt-register.md) tracks the related report-composition-agent gap.
