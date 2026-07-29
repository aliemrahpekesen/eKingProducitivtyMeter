# ADR-018: Isolate object-storage tenants with shared buckets, a mandatory tenant-id key prefix, and application-enforced scoping

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [SecurityModel](../architecture/SecurityModel.md) (storage isolation); the storage SPI in `eip-core.storage`, `eip-reports`; CC-1.

## Context

Recorded by the architecture readiness review, 2026-07-06 ([ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md), forced by MTR-02). SecurityModel offered an undecided either/or — "bucket-per-tenant **or** prefix + per-tenant credentials" — leaving the object-storage isolation (D4) mechanism to be chosen ad hoc by implementers.

## Decision

Use shared buckets (`eip-ingest`, `eip-artifacts`) with a mandatory `<tenantId>/...` key prefix and application-enforced scoping in the single storage service, with access audited. Per-tenant MinIO credentials are not used; the residual risk is accepted and compensated by a storage-prefix isolation test added to the NFR-041 isolation suite.

## Consequences

- **Positive:** one decided, enforced isolation mechanism instead of an ad-hoc either/or; no per-tenant credential sprawl to provision and rotate; the NFR-041 isolation suite guards the prefix boundary continuously.
- **Negative:** isolation is application-enforced rather than credential-enforced at the store (an accepted risk); a bug in the scoping layer is the failure mode, which is why the periodic prefix-isolation test is mandatory.

## Alternatives rejected

- **Bucket-per-tenant** — rejected: bucket sprawl plus per-bucket lifecycle and quota management overhead at tenant scale.
- **Prefix plus per-tenant MinIO credentials** — rejected: per-tenant credential provisioning and rotation overhead; the single-mechanism model with a compensating isolation test was chosen instead.
- **Leaving the either/or undecided** — rejected: implementers would pick divergent mechanisms, producing inconsistent isolation.
