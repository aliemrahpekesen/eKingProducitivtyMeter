# ADR-002: Adopt Apache Kafka (KRaft) as the event backbone with the `eip.` topic prefix

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [EventModel](../engineering/EventModel.md) (topic catalog, envelope); `eip-ingestion`, `eip-analytics`, `eip-ai`, `eip-reports`, `eip-workers`; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). The pipeline must be replayable and decoupled across failure domains, sustain the ingestion throughput target (NFR-003) with burst headroom, and preserve per-entity ordering. On-premise footprint (D6) argues against every extra stateful service.

## Decision

Use Apache Kafka in KRaft mode as the event backbone; all topics carry the `eip.` prefix and are partitioned by the `tenantId:entityId` key so per-key ordering is preserved. Topic catalog and envelope live in [EventModel](../engineering/EventModel.md).

## Consequences

- **Positive:** replayable at-least-once streams enable read-model rebuilds and recompute; decoupled producers/consumers isolate failure domains; KRaft removes ZooKeeper, cutting one stateful service from the on-prem footprint (D6).
- **Negative:** Kafka remains a stateful service enterprises must operate; at-least-once delivery forces every consumer to be idempotent (ADR-010); partition-key choice fixes the ordering contract and cannot change without a migration.

## Alternatives rejected

- **A database-backed queue / polling pipeline** — rejected: no log replay for recompute and poor decoupling at the ingestion volume the platform targets (NFR-003).
- **Kafka with ZooKeeper** — rejected: an additional stateful coordination service on the on-prem footprint (D6) that KRaft makes unnecessary.
- **A non-log broker (e.g. classic AMQP)** — rejected: weaker retained-log/replay semantics, which the analytics and RAG recompute paths depend on.
