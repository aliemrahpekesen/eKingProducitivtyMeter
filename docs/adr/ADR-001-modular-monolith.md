# ADR-001: Build the backend as a Spring Modulith modular monolith with a separately deployable worker runtime

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [ArchitectureOverview §5](../architecture/ArchitectureOverview.md) module map & dependency rules; [BackendPlan §1](../engineering/BackendPlan.md); all nine `eip-*` modules; CC-1 (architectural structure).

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). The dominant driver is on-premise operability (D6): the platform ships into strict enterprise and air-gapped networks where every additional deployable is an operational cost. Ingestion needs transactional boundaries between state changes and event emission, and the architecture must retain seams that can later be extracted to services without a rewrite ([ArchitectureOverview §2.2](../architecture/ArchitectureOverview.md)).

## Decision

Build the backend as a single Spring Modulith modular monolith deployed as `eip-app`, with `eip-workers` as a second deployable runtime that composes the same modules but activates only asynchronous work (Kafka consumers, sync jobs, agent executors, report generators). Module boundaries are enforced by `ModularityTests`, not convention.

## Consequences

- **Positive:** one primary deployable to operate, back up, and reason about (D6); in-process transactional boundaries for ingestion; Modulith-verified named-interface seams that make later service extraction mechanical rather than a rewrite.
- **Negative:** modules share a JVM and process, so a severe fault in one can affect the whole app; boundary discipline must be continuously enforced by tests rather than by network separation; scaling is coarse-grained (whole-app) until a module is extracted — partially mitigated by the separate `eip-workers` runtime for async load.

## Alternatives rejected

- **Microservices from day one** — rejected for operational cost against D6 (many deployables, service mesh, distributed transactions across the ingestion write/emit boundary) with no offsetting benefit at the target scale.
- **A single monolith with no internal module boundaries** — rejected because it provides no extraction seams and lets dependency edges drift silently; the Modulith verification is precisely what keeps the "service-ready" promise real.
