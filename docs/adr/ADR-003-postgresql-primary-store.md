# ADR-003: Use PostgreSQL 16 as the single primary store with Flyway migrations

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [DatabasePlan](../engineering/DatabasePlan.md) (physical model, migrations); all persistence-owning modules; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). On-premise operability (D6) rewards a single datastore to operate, back up, and tune. Canonical normalization needs ACID upserts, and enterprises expect a mature HA/DR story. Polyglot persistence multiplies the on-prem operational surface.

## Decision

PostgreSQL 16 is the single primary store; all schema changes flow through Flyway forward-only migrations (`V<seq>__*.sql`), with schema truth living in Flyway rather than Hibernate `ddl-auto`.

## Consequences

- **Positive:** one database to back up, patch, and tune (D6); ACID canonical upserts; pgvector rides in the same engine (ADR-005); Postgres RLS gives structural tenant isolation (ADR-004).
- **Negative:** one engine must serve OLTP writes, analytics reads, and vector search at once — mitigated by CQRS-lite read models (ADR-011), read-only analytics grants (ADR-019), and partitioning of high-volume tables; a single-primary write ceiling is accepted for the target scale and addressed by read-side offloading before sharding.

## Alternatives rejected

- **Polyglot persistence** (separate OLTP, analytics warehouse, and search stores) — rejected: multiplies the number of stateful services to operate and secure on-prem (D6) with little benefit at the target scale.
- **A NoSQL primary** — rejected: loses ACID canonical upserts and the RLS-based structural tenant isolation that ADR-004 depends on.
