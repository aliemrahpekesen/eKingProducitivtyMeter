# Module: eip-core

- **Root package:** `com.eip.core`
- **Owner:** R-BA
- **Allowed dependencies:** (none — leaf)
- **State:** Phase-0 skeleton (build wiring + package root only; no domain content yet — TASK-0001).

## Purpose

Shared kernel: canonical entities (WorkItem supertype, ExternalRef), event envelope, UUIDv7 IDs, time/grain types, error taxonomy, secrets/KMS SPI, object-storage and VectorStore SPIs. Content: TASK-0005 (P0-E2-S1, CC-1).

## Owned tables / topics / endpoints

None yet. Ownership is declared per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md) (tables) and [EventModel §3](../../docs/engineering/EventModel.md) (topics) as content lands in later phases; this charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.core.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
