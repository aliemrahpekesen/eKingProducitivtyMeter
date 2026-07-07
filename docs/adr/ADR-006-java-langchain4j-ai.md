# ADR-006: Orchestrate AI in Java with LangChain4j; allow Python only in optional isolated workers

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [AgentArchitecture](../ai/AgentArchitecture.md); `eip-ai`; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). A second production runtime is a real operational cost on-prem (D6). The agent runtime needs typed tool-calling and to share the platform's transactional, security, and observability context.

## Decision

AI orchestration is implemented in Java with LangChain4j inside `eip-ai`. Python is permitted only behind Kafka or REST as optional, isolated workers — never on the synchronous agent path.

## Consequences

- **Positive:** one runtime, one deployment and security story (D6); typed tool-calling and direct reuse of the platform's tenancy/observability context; the LLM provider SPI keeps model backends swappable.
- **Negative:** the Java AI ecosystem is younger than Python's; capabilities that exist only in Python must be run as isolated workers behind a queue, adding indirection when they are needed.

## Alternatives rejected

- **A Python-first AI service** — rejected: doubles the on-prem operational surface with a second runtime to deploy, secure, and observe (D6).
- **Polyglot orchestration with Python in the hot path** — rejected: cross-runtime coupling on the synchronous request path defeats the single-deployment-story goal.
