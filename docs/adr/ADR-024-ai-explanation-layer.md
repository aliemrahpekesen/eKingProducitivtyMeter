# ADR-024: A thin, opt-in AI explanation layer — LLM SPI now, LangChain4j later

- **Status:** Accepted
- **Approver:** Founder direction, ratified R-CA (2026-07-21, TASK M6-A)
- **Affected anchors/modules:** [SecurityModel §4](../architecture/SecurityModel.md) (`ai.agent.invoke`/`ai.policy.manage` permissions), [DatabasePlan §2](../engineering/DatabasePlan.md) (`ai.llm_call_audit`), [BackendPlan §1](../engineering/BackendPlan.md) (`eip-ai` allowed-dependency table); `eip-ai` (new: `spi`, `providers`, `api`, `application`, `persistence`), `eip-tenancy` (`rbac`), `eip-app` (`/api/v1/ai/*`, `/api/v1/insights/explain`, `/api/v1/reports/{id}/narrative`); CC-1 (schema/migration V7, published SPI `LlmClient`).

## Context

FR-082 commits the product to 18 runtime AI agents eventually, but NFR-071 (release-blocking) and
the founder's operating law are unambiguous: EIP is **deterministic-first**. Every number a user
sees must already have been computed by a deterministic engine (friction, trends, recommendations,
reports) before any AI touches it. AI may only **explain** — narrate already-computed, team-level
results in prose — never compute a metric, never see individual-level data (none exists in the
canonical model to begin with), and must be entirely optional: off by default per tenant, with zero
external egress unless the tenant explicitly configures a provider endpoint (air-gap fit, D6).

`eip-ai`'s skeleton (Phase 0) and ADR-006 already commit the *eventual* agent runtime to
LangChain4j in Java. Building that now — agent orchestration, RAG, MCP client/server — is
premature: M6-A needs exactly two things a dashboard/report consumer can trust: "explain what I'm
already looking at" and a hard guarantee that the explanation cannot smuggle in a fabricated number.

## Decision

Ship a minimal, framework-thin explanation layer inside `eip-ai`, deferring LangChain4j adoption to
the later RAG/agent phase ADR-006 already committed to:

- **LLM SPI 0.1** (`com.eip.ai.spi.LlmClient`, semver'd per NFR-060): one method,
  `complete(LlmRequest) -> LlmResponse`. Two implementations ship — `OllamaClient` (the
  air-gap-friendly default) and `OpenAiCompatibleClient` (any OpenAI-compatible endpoint) — both
  built on a **pure JDK `HttpClient`** helper (`AiHttp`, mirroring `eip-connectors`'
  `SourceHttp` style). No new dependency is introduced for this slice.
- **Opt-in, default-OFF per tenant** (`core.tenant_ai_policy`, V7 migration): `enabled` defaults to
  `false`; enabling requires an explicit provider + base URL + model, so a tenant that never
  configures anything gets zero egress, ever.
- **Deterministic-only composition** (`PromptComposer`): both `explain(weeks)` and
  `narrate(reportId)` build their prompt from the SAME composed reads the dashboard/report already
  shows (`eip-analytics`'s friction/trends/recommendations query ports; the report's own
  totals/team sections) — never a recomputed value, never anything the deterministic engines
  themselves have not already produced and shown.
- **The numeric cross-check gate** (`NumericCrossChecker`): the whitelist of "numbers this call is
  allowed to cite" is derived by running the same extraction over the composed prompt text that
  later verifies the narrative — so it is, by construction, exactly "every number offered to the
  LLM." Every number the returned narrative cites must appear in that whitelist, normalized
  (`85%` ↔ `85`, `17.0` ↔ `17`), with **no exemptions** — a narrative citing anything else is
  discarded (`NarrativeRejectedException`) rather than returned. This is the platform's actual
  guardrail against a hallucinated number reaching a user; a token-level check has known,
  documented imprecision (small ordinals, date-embedded digits) rather than false confidence from
  semantic matching we have not built.
- **Hash-only audit, always written** (`ai.llm_call_audit`, V7 migration): one row per call attempt
  regardless of outcome (`OK`/`FAILED`/`REJECTED`), carrying only `prompt_sha256`/`prompt_chars`
  and `response_sha256`/`response_chars` — never the prompt or response text. The prompt embeds the
  tenant's own metric data and the response is LLM output; the audit proves a call happened and its
  size/shape, never its content (log-hygiene — the same discipline CLAUDE.md requires of
  structured logs applies to this persisted ledger too).
- **Stateless narratives**: nothing is persisted beyond that audit row — `ExplanationView` is
  computed and returned, never stored, mirroring ADR-023's "ship the useful v0.1 shape, not the
  full planned schema" pattern (`core.tenant_ai_policy`/`ai.llm_call_audit` are themselves a
  deliberate subset of the fuller `ai.llm_provider`/`ai.agent_run` schema DatabasePlan §2
  anticipates for the agent-runtime phase).

Two deviations from a literal reading of the task, both forced by the existing module graph and
recorded here rather than silently:

1. **`NarrateReportUseCase` takes the report's content directly, not a `reportId`.** `eip-reports`'
   own `build.gradle.kts` already declares `implementation(project(":eip-ai"))` (for the future
   Report Composition agent invocation, BackendPlan §1's `eip-reports` row) — so `eip-ai` depending
   on `eip-reports` back would be a circular Gradle project dependency (a hard failure, not a style
   choice), and BackendPlan §1's `eip-ai` row lists only `eip-core`/`eip-tenancy`/`eip-analytics` as
   allowed dependencies regardless. `eip-app`'s `AiExplainController` reads the report via
   `eip-reports`' `GetReportQuery` first and maps it onto `eip-ai`'s own `ReportNarrativeInput` (a
   field-for-field mirror of `ReportTotals`/`ReportTeamSection`) before calling in — the same kind
   of cross-module DTO translation `com.eip.reports.persistence.ReportCursor`'s javadoc already
   documents as "deliberate, acknowledged duplication" for an analogous constraint.
2. **The `eip.ai.calls{purpose,status}` counter is narrower than the already-documented
   `eip_llm_calls_total{tenantId,provider,model,agent,outcome}` metric** in
   [ObservabilityModel §3](../architecture/ObservabilityModel.md). That fuller metric anticipates
   the agent runtime (per-agent breakdown, token/cost tracking) this ADR explicitly defers; shipping
   it now would mean either fabricating an `agent` label with no agents behind it, or leaving cost
   tracking unimplemented under a name that promises it. `eip.ai.calls{purpose,status}` is scoped to
   exactly what M6-A has: which action was called and how it ended. Reconciling the two names is
   registered as [DEBT-023](../../work/debt-register.md), to land with the agent runtime.

## Consequences

- **Positive:** zero new dependencies (pure JDK `HttpClient`, mirroring the connector-framework
  style already established); works fully air-gapped against a self-hosted Ollama endpoint;
  default-off per tenant means the platform's out-of-the-box behavior has zero AI egress; the
  numeric cross-check is a real, enforced gate — not a prompt instruction the model can ignore — so
  a hallucinated number cannot reach a dashboard even if a provider misbehaves; the schema is
  forward-compatible (V7's two tables are additive; the agent-runtime phase's fuller `ai.llm_provider`/
  `ai.agent_run` schema can land alongside without touching what M6-A already writes).
- **Negative:** no RAG, no agent orchestration, no MCP — `com.eip.ai.spi`/`providers` will be
  superseded by a LangChain4j-backed implementation when that phase lands (ADR-006), so this SPI's
  0.1 shape should be expected to evolve, not treated as final; the numeric cross-check is
  token-level, not semantic, with the imprecision `NumericCrossChecker`'s javadoc documents; the
  `eip.ai.calls` metric will need reconciling with `eip_llm_calls_total` (DEBT-023);
  `ReportNarrativeInput` duplicates `ReportTotals`/`ReportTeamSection`'s shape rather than reusing
  the types directly, an accepted cost of the one-way `eip-reports → eip-ai` dependency edge.

## Alternatives rejected

- **Build the LangChain4j agent runtime now** — rejected: M6-A needs two provider adapters and a
  cross-check, not agent orchestration, RAG, or MCP; pulling in the full runtime for this slice is
  premature infrastructure the founder's "tiny optional layer" framing explicitly rules out.
- **Let the LLM call recompute or re-derive any number** (e.g. "compute the trend yourself from
  these raw points") — rejected outright: violates the deterministic-first law. Every number the
  layer can ever cite must already have been computed by a deterministic engine before the prompt is
  built.
- **Trust the model's own instruction-following instead of a hard numeric check** — rejected:
  system-prompt guardrails are necessary but not sufficient; an unenforced "use only these numbers"
  instruction is exactly the kind of AI-behavior risk (CC-5) this platform does not ship without a
  mechanical gate.
- **`eip-ai` depends on `eip-reports` directly so `narrate` can take a bare `reportId`** — rejected:
  a circular Gradle project dependency (both declared, both real) and a contract-anchor violation
  (BackendPlan §1); see the Decision section's first deviation.
