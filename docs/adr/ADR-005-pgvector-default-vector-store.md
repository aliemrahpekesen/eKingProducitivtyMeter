# ADR-005: Default to pgvector for vector storage; keep Qdrant pluggable behind the VectorStore SPI

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [DatabasePlan](../engineering/DatabasePlan.md) (pgvector), the VectorStore SPI in `eip-core.vector`; `eip-ai` (RAG); CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). The default install should add zero infrastructure beyond the primary datastore (D6), while very large corpora need an escape hatch to a purpose-built vector engine.

## Decision

pgvector is the default vector store, embedded in the primary PostgreSQL (ADR-003); the `VectorStore` SPI keeps Qdrant available as a pluggable high-scale alternative. Vector-store tenancy is settled separately in ADR-016.

## Consequences

- **Positive:** no extra service for the default install (D6); embeddings share the same backup, RLS, and operational story as the rest of the data.
- **Negative:** pgvector has a practical scaling ceiling; installs beyond it must adopt Qdrant, which changes the tenancy mechanism (ADR-016) and adds a stateful service; the SPI must be kept genuinely provider-neutral or the escape hatch rots.

## Alternatives rejected

- **A dedicated vector database (e.g. Qdrant) by default** — rejected: an extra stateful service for every install, most of which never reach the scale that needs it (D6).
- **pgvector only, no SPI** — rejected: leaves high-scale installs with no supported path off pgvector, forcing a later re-architecture.
