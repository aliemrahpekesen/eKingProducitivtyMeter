# ADR-010: Guarantee at-least-once delivery with idempotent consumers and a transactional outbox

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [EventModel](../engineering/EventModel.md) (delivery semantics, outbox, DLQ); `eip-core.events`, `eip-ingestion`; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). A state change and its emitted event must not diverge (the dual-write problem), and delivery must survive broker/consumer restarts. Kafka exactly-once transactions spanning the database and broker are operationally fragile on-prem (D6).

## Decision

Adopt at-least-once delivery with idempotent consumers that deduplicate on `eventId` (UUIDv7); domain, analytics, and job events are emitted through a transactional outbox written in the same transaction as the state change. The precise outbox scope and the raw-intake exception are refined in ADR-017.

## Consequences

- **Positive:** effective exactly-once processing via idempotency + dedup, without the operational cost of broker/DB EOS transactions (D6); the outbox closes the dual-write gap so no committed change loses its event.
- **Negative:** every consumer must implement idempotency and maintain a dedup ledger; the outbox relay is an additional moving part with its own retry/DLQ behaviour to operate.

## Alternatives rejected

- **Kafka exactly-once semantics (EOS) transactions across DB and broker** — rejected: complex and fragile to operate on-prem for the reliability it buys (D6); idempotent consumers achieve the same effective guarantee more simply.
- **Fire-and-forget publishing after commit** — rejected: a crash between commit and publish silently loses the event (the dual-write gap the outbox exists to close).
