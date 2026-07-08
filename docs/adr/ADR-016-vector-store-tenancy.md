# ADR-016: Isolate vector-store tenants by RLS on pgvector and one Qdrant collection per tenant per embedding space

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [SecurityModel](../architecture/SecurityModel.md), [RAGArchitecture](../ai/RAGArchitecture.md); the VectorStore SPI in `eip-core.vector`, `eip-ai`; CC-1.

## Context

Recorded by the architecture readiness review, 2026-07-06 ([ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md), forced by MTR-01 + RAG-01). SecurityModel §5 required "separate collections per tenant" while RAGArchitecture described "one collection per model, shared, filtered" — a direct contradiction on a tenant-isolation (D4) mechanism that FR-128 makes release-blocking.

## Decision

pgvector (the default) isolates via RLS on `ai.rag_chunk`. Qdrant (pluggable) uses one collection per tenant per embedding space, named `eip_<tenantId>_<embeddingSpace>`. The SPI-level tenant filter and a result-side recheck are retained as additional defense-in-depth layers on both backends.

## Consequences

- **Positive:** three isolation layers (store-native, SPI filter, result recheck) keep structural isolation (D4) intact across both vector backends; the contradiction is resolved with one rule per backend.
- **Negative:** per-tenant-per-embedding-space Qdrant collections multiply the collection count and its management overhead at high tenant scale; the result-side recheck adds a step to every retrieval.

## Alternatives rejected

- **One shared Qdrant collection per model, filtered by tenant metadata** — rejected: a single filter-construction bug leaks cross-tenant chunks, which is insufficient isolation for the D4/FR-128 boundary.
- **Relying on a single isolation layer** — rejected: no defense-in-depth on a security-critical boundary; the layered model survives a fault in any one layer.
