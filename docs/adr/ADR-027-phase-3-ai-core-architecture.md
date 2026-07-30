# ADR-027: Phase 3 "AI Core" architecture — sequencing the agent runtime

- **Status:** Proposed
- **Approver:** _Pending founder ratification._ CC-1 (contract-anchor — touches [DatabasePlan §2](../engineering/DatabasePlan.md)'s already-named `ai.*`/`reports.*` schema and the [AgentArchitecture](../ai/AgentArchitecture.md) spec) per [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md): G4 requires owning-architect (R-AIA) + R-CA approval, G8 additionally requires R-CR. This ADR ships **Proposed**, mirroring [ADR-023](ADR-023-deterministic-reports-inline-document.md)/[ADR-024](ADR-024-ai-explanation-layer.md)'s own "Founder direction, ratified R-CA" header pattern — it becomes Accepted only on that explicit ratification, and no TASK-0027..0034 (below) may be claimed until it does.
- **Affected anchors/modules:** [PRD §5.5](../product/PRD.md) (FR-080–092), [DatabasePlan §2](../engineering/DatabasePlan.md) (`ai.llm_provider/agent_run/agent_step/rag_document/rag_chunk/rag_chunk_embedding_*/rag_index_state/rag_acl_grant/rag_retrieval_audit/mcp_capability_grant`, `reports.report_job/report_template/report_schedule/report_subscription`), [BackendPlan §9](../engineering/BackendPlan.md) (`eip.ai.jobs`/`eip.ai.results`/`eip.reports.jobs` topics), [DataFlow §7](../architecture/DataFlow.md) / [DomainModel §11](../architecture/DomainModel.md) (report job state machine), [ObservabilityModel §3](../architecture/ObservabilityModel.md) (`eip_llm_calls_total`/`eip_llm_tokens_total`/`eip_llm_cost_estimate`/`eip_agent_runs_total`); `eip-ai`, `eip-reports`, `eip-workers` (Phase-0 skeleton → real deployable), `eip-core` (`com.eip.core.storage` SPI, reserved-empty); CC-1 (multiple new schemas, a published SPI evolution, a new deployable).

## Context

DEBT-021 (AI-composed report job-runner/MinIO/LangChain4j) and DEBT-023 (full `eip_llm_calls_total`
metric schema) were both deliberately deferred by [ADR-024](ADR-024-ai-explanation-layer.md) to "the
later agent-runtime phase" — a phrase that, until now, nobody had scoped as actual engineering work.
[ADR-006](ADR-006-java-langchain4j-ai.md) already commits the platform to LangChain4j for that phase;
[PRD §5.5](../product/PRD.md) already commits the product to it under FR-080–092 (an agent runtime
with plan/execute agents, tool-calling, per-run budgets, and guardrails; async jobs via `eip-workers`;
18 canonical agents per FR-082, minimum Sprint Review + Release Notes + Delivery Risk + Validation
Agent v1 in Phase 3 per FR-083); [PhaseBasedImplementationPlan §8](../implementation/PhaseBasedImplementationPlan.md)
already sizes each epic **L** under **Phase 3 ("AI Core")**. What has never existed is the bridge
between "the ADRs and PRD commit to this" and "here is how it gets built against the code that
exists today" — that bridge is this ADR.

The gap is real, not just unscoped: `DatabasePlan §2`'s table-placement catalog already *names*
`ai.llm_provider`, `ai.agent_run`, `ai.agent_step`, `ai.rag_document`, `ai.rag_chunk`,
`ai.rag_chunk_embedding_*`, `ai.rag_index_state`, `ai.rag_acl_grant`, `ai.rag_retrieval_audit`,
`ai.mcp_capability_grant`, and `reports.report_job`/`report_template`/`report_schedule`/
`report_subscription`, but defines none of their DDL. `BackendPlan §9` pre-names Kafka topics
`eip.ai.jobs`/`eip.ai.results`/`eip.reports.jobs` and consumer groups `eip.ai.orchestrator`/
`eip.reports.job-runner` that nothing produces or consumes. `eip-workers`' own `MODULE.md` already
calls itself "Phase-0 skeleton... no domain content yet (SPRINT-05)" — a second Spring Boot
composition root, dependency-wired to every module, with no worker `main` class yet. And the
existing M6-A work (ADR-024) already reserved the exact seam this phase needs: `com.eip.ai.spi`'s
`LlmClient` interface is semver'd (NFR-060) with its own javadoc stating plainly "a LangChain4j-backed
implementation becomes the default when RAG/agent phases land" — this ADR is that phase arriving,
not a rewrite of what M6-A shipped.

This ADR does not propose building all of Phase 3 in one task. It sequences it into eight bounded
TASKs (below), each independently claimable by a future session following this repo's own
`TASK-0016`→`TASK-0026` granularity, so no single session inherits an L-sized epic it cannot finish
inside one context window with the same "single-writer, independently-gated, live-verified" rigor
every prior task in this register was held to.

## Decision

Sequence Phase 3 as nine sub-decisions, each the scope of one or more TASKs:

1. **LangChain4j swap behind the existing SPI 0.1 shape.** `com.eip.ai.spi.LlmClient.complete(LlmRequest) -> LlmResponse`
   does not change; `com.eip.ai.providers`' pure-JDK-`HttpClient` `OllamaClient`/`OpenAiCompatibleClient`
   are superseded by LangChain4j-backed implementations behind the same interface. This is exactly
   what the SPI's own javadoc reserved — no SPI break, no caller (`ExplainService`) changes.
   → **TASK-0027**.
2. **MinIO storage SPI — first real implementation.** `com.eip.core.storage` is a reserved,
   interface-free package today (`package-info.java` only). [ADR-007](ADR-007-minio-object-storage.md)
   already chose MinIO as the default S3-compatible store; [ADR-018](ADR-018-object-storage-tenancy.md)
   already chose the tenancy model above it. The MinIO *service* and its buckets (`eip-artifacts`,
   `eip-ingest`) already run in dev infra (`infra/docker-compose/docker-compose.yml`) — only the Java
   client/abstraction is greenfield. Land this first, wired into the *existing* deterministic
   `EXEC_SUMMARY` report path (writing a real `artifact_keys` entry) before any AI-composed report
   type needs it, so the storage SPI is proven against a low-risk caller first. → **TASK-0028**.
3. **Report engine v2.** `reports.report_job`/`report_template`/`report_schedule`/`report_subscription`,
   shaped by the job state machine `PENDING → RUNNING → VALIDATING → RENDERED → DELIVERED | FAILED`
   documented in `DataFlow §7`/`DomainModel §11` — **not** `DatabasePlan §3`, which has no DDL for
   these tables yet and is not the source of record here. Populates `reports.generated_report`'s
   `llm_call_ids`/`citations`/`validation_status` for AI-composed report types once agents exist to
   produce them (TASK-0033). The existing deterministic `EXEC_SUMMARY` path (`ReportGenerationService`,
   `ScheduledReportRunner`) is **explicitly unaffected** — it stays synchronous/inline; only new
   AI-composed report types route through the job-runner. → **TASK-0029**.
4. **`eip-workers` becomes a real deployable.** Its own `MODULE.md` already names this "SPRINT-05":
   gains `eip.boot-app-conventions`, its own `bootRun`/container image, and activates Kafka consumers
   for `eip.ai.jobs`/`eip.ai.results`/`eip.reports.jobs`. Agent execution and report-job processing
   live **here**, never as additional `eip-app` `@Scheduled` beans — `ScheduledReportRunner`'s own
   javadoc already flags a cron-fanout-loop as inadequate for real job-runner semantics ("cheap and
   deterministic... ADR-023 defers a real job-runner to the AI-composed report types"). This is an
   infra prerequisite for 5–7, not a debt-closing step itself. → **TASK-0030**.
5. **Agent runtime skeleton in `eip-ai`.** `ai.llm_provider`/`ai.agent_run`/`ai.agent_step`; the typed
   tool-calling contract already sketched in [AgentArchitecture §4](../ai/AgentArchitecture.md)
   (JSON-Schema in/out, RBAC requirement, tenant scope, result-size limits, artifact sanitization) and
   the `LlmProvider`/`ModelDescriptor` SPI sketch in its §7; per-run budgets (token/cost/time/steps)
   and guardrails per FR-088. Executed from `eip-workers` (TASK-0030), not `eip-app`. → **TASK-0031**.
6. **RAG pipeline.** pgvector-backed ([ADR-005](ADR-005-pgvector-default-vector-store.md) already the
   default vector store); `ai.rag_document`/`rag_chunk`/`rag_chunk_embedding_*`/`rag_index_state`/
   `rag_acl_grant`/`rag_retrieval_audit`; permission-aware retrieval per FR-095–101 — retrieval must
   never surface a chunk the caller's RBAC/tenant scope would not otherwise let them read (mirrors the
   resource-level team-scope pattern `TeamScopeFilter` already established for friction summaries).
   → **TASK-0032**.
7. **First agents: the FR-083 minimum, not all 18.** Sprint Review, Release Notes, Delivery Risk +
   Validation Agent v1 (gates publication of every user-facing agent output — no agent output ships
   without passing it, mirroring M6-A's `NumericCrossChecker` gate at a coarser grain).
   [AgentArchitecture §5](../ai/AgentArchitecture.md)'s remaining 14 canonical agents, MCP client/server,
   and generated diagrams/presentations stay **Phase 4 — explicitly out of scope for this ADR**.
   → **TASK-0033**.
8. **Observability reconciliation.** `eip_llm_calls_total{tenantId,provider,model,agent,outcome}`,
   `eip_llm_tokens_total`, `eip_llm_cost_estimate`, `eip_agent_runs_total` (`ObservabilityModel §3`,
   exact rows) replace/extend M6-A's `eip.ai.calls{purpose,status}` once real agents exist to populate
   an honest `agent` label — shipping that label early with nothing behind it is exactly what
   ADR-024 correctly refused to do. This is DEBT-023's actual closure point. → **TASK-0034**.
9. **The `eip-ai` ↔ `eip-reports` circular-dependency constraint stays, unchanged.** `eip-reports`
   already depends on `eip-ai` (`build.gradle.kts`); the edge can never invert without its own ADR.
   The Report Composition Agent's read of report state continues through `eip-app`-level DTO
   translation (mirroring `AiExplainController`'s existing pattern for `NarrateReportUseCase`) or
   lives in `eip-workers` calling both modules' `.api` ports directly — never a direct
   `eip-ai → eip-reports` Gradle edge. No TASK below may introduce that edge.

## Consequences

- **Positive:** turns an unscoped "later" into eight bounded, independently gated TASKs, each sized
  to fit this repo's own established single-session discipline; builds on the SPI/schema seams M6-A
  and ADR-023 already deliberately reserved (`LlmClient`'s javadoc, `document jsonb`'s
  forward-compatible additive shape) rather than reworking them; sequences the highest-leverage,
  lowest-risk pieces first (LangChain4j swap and MinIO wiring touch existing, working, low-traffic
  paths before any agent or RAG code exists to depend on them).
- **Negative:** a new deployable (`eip-workers`) to operate — CI/CD, Helm values, and observability
  surface all grow; new attack surface once RAG retrieval and tool-calling exist (prompt-injection and
  tool-allowlist controls already spec'd in [MCPArchitecture](../ai/MCPArchitecture.md) apply here in
  spirit even though MCP itself stays Phase 4 — a future TASK touching tool-calling should re-read
  `SecurityModel`'s STRIDE table before landing it); this ADR by design leaves DEBT-021 and DEBT-023
  open until TASK-0028/0029/0033 and TASK-0034 respectively land — it is a roadmap, not a closure.

## Alternatives rejected

- **Build all of Phase 3 as one task** — rejected: `PhaseBasedImplementationPlan §8` sizes each
  constituent epic **L** individually; a single task spanning LLM SPI rework, RAG, agent runtime,
  three agents with validation, and a report-engine rewrite cannot be held to this repo's own
  independent-gate-per-task discipline (fresh `check --rerun-tasks`, live verification, single
  reviewer) inside one session.
- **Skip the ADR and let each TASK improvise its own slice of the schema/SPI** — rejected: the
  cross-cutting constraints (the `eip-ai`↔`eip-reports` edge, `eip-workers` as the execution home not
  `eip-app`, the SPI-swap-not-rewrite seam) are exactly the kind of thing that goes wrong when
  discovered independently by unrelated future sessions rather than decided once, up front, and cited
  by every task that touches them.
- **Have `eip-app` host agent execution via more `@Scheduled` beans (mirroring `AuditChainerScheduler`/
  `OutboxRelayScheduler`)** — rejected: those are lightweight per-tenant sweeps a single request-serving
  process can absorb; an agent run (LLM calls, RAG retrieval, tool invocation, potentially minutes of
  wall-clock) is exactly the workload `eip-workers` was reserved for since Phase 0, and `BackendPlan`
  already documents `eip-app` as consuming no Kafka in the target design.
