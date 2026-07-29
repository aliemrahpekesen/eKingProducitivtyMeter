# ADR-012: Use Redis 7 (Redisson) for distributed locks, rate-limit budgets, and idempotency state

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [BackendPlan](../engineering/BackendPlan.md) (resilience, locks, distributed state); `eip-core` infra tables note, `eip-connectors`; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Sync-scheduler leases and connector rate limiters need low-latency shared state that both `eip-app` and `eip-workers` see, on hot paths where a database round-trip per check is too slow.

## Decision

Use Redis 7 via Redisson for distributed locks, distributed rate-limit budgets, and fast idempotency/dedup state. Redis is treated as fail-open: on cache loss the platform stays correct (degraded, not broken).

## Consequences

- **Positive:** low-latency shared coordination across both runtimes; connectors share a rate-limit budget so one tenant's full sync cannot starve others; leases prevent duplicate scheduled work.
- **Negative:** Redis is another service to operate; every use must be designed fail-open so a Redis outage degrades performance or loosens a limit rather than corrupting state or blocking the platform.

## Alternatives rejected

- **Database advisory locks / DB-based rate limiting** — rejected: too slow for the hot connector and scheduler paths, adding contention to the primary store.
- **In-process-only locks** — rejected: they do not coordinate across the separate `eip-app` and `eip-workers` runtimes, so scheduled work and rate limits would double up.
