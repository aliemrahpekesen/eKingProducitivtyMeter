# ADR-007: Use MinIO as the default S3-compatible object store behind a storage abstraction

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** the object-storage SPI in `eip-core.storage`; `eip-reports` (artifact library); CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Artifacts, exports, and raw payloads need durable object storage that works in air-gapped installs (D6), while enterprises frequently already run an S3-compatible appliance they want to reuse.

## Decision

MinIO is the default S3-compatible object store; all access goes through a storage abstraction so an enterprise S3-compatible endpoint can replace MinIO by configuration. Object-storage tenancy is settled in ADR-018.

## Consequences

- **Positive:** on-prem parity with the S3 API out of the box (D6); the abstraction lets enterprises swap in their own appliance without code changes.
- **Negative:** MinIO is another stateful service to operate and back up for the default install; multi-tenant isolation must be enforced above the store (ADR-018) since a shared-bucket model is used.

## Alternatives rejected

- **A plain filesystem / NFS store** — rejected: no S3 API, weaker multi-node and lifecycle semantics that the artifact library and exports rely on.
- **A hard dependency on cloud S3** — rejected: air-gapped installs have no cloud endpoint (D6); the abstraction still allows cloud S3 where policy permits.
