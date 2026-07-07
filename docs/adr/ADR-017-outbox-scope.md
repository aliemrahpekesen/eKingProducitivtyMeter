# ADR-017: Scope the transactional outbox to domain/analytics/job events and publish raw intake directly with staged-row durability

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [EventModel](../engineering/EventModel.md) (outbox, DLQ), [DataFlow](../architecture/DataFlow.md); `eip-ingestion`, `eip-core.events`; CC-1.

## Context

Recorded by the architecture readiness review, 2026-07-06 ([ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md), forced by SBR-04 + EDA-01). EventModel §9 stated "no producer ever publishes directly" while DataFlow Flows A/C showed direct raw produces — a contradiction. A second defect: a single relay runner would stall `eip-app`-originated events whenever workers were down. The webhook freshness budget (NFR-012) and ingestion throughput (NFR-003) are in play.

## Decision

The transactional outbox is mandatory for domain, analytics, and job events (`eip.domain.*`, `eip.analytics.metrics`, `eip.ai.*`, `eip.reports.*`). Raw intake (sync emitters + webhook intake) publishes directly to `eip.raw.<connector>`, with the staged `raw_*` row written before the produce as durability and `staging.webhook_intake_buffer` as the Kafka-outage buffer. `OutboxRelay` runs in both runtimes, each relaying only its own writes.

## Consequences

- **Positive:** keeps outbox latency off the webhook path so the freshness budget (NFR-012) holds and the outbox is sized for domain volume, not raw volume; the staged-row-before-produce rule closes the raw-path dual-write gap; app-originated events never stall when workers are down.
- **Negative:** two emission paths must be understood and tested (transactional outbox vs direct raw); the raw path's durability rests on the staged row plus the intake buffer rather than the outbox's single-transaction guarantee.

## Alternatives rejected

- **Route every producer through the outbox, including raw intake** — rejected: adds outbox latency to the webhook path (missing NFR-012) and sizes the outbox for high raw volume.
- **Publish everything directly with no outbox** — rejected: reopens the domain-event dual-write gap that ADR-010 closes.
- **A single shared relay runner** — rejected: it stalls `eip-app` events whenever the workers runtime is down; per-runtime relays remove that coupling.
