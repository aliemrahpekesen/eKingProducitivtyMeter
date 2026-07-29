# Module: eip-connectors

- **Root package:** `com.eip.connectors`
- **Owner:** R-CNA
- **Allowed dependencies:** eip-core, eip-tenancy
- **State (TASK-0016):** v0.1 Connector SPI (`spi`) + deterministic `SimulationConnector`/`SimulationDataset` (`simulation`) — framework-free pure Java (DEBT-018 tracks the full lifecycle SPI). Spring Modulith OPEN module; Spring wiring lives in eip-ingestion's `SimulationSourceConfiguration`.
- **State:** Phase-0 skeleton (build wiring + package root only; no domain content yet — TASK-0001).

## Purpose

Connector SPI plus all built-in connectors incl. simulation mode: JSON Schema config, validate(), testConnection(), healthCheck(), fullSync(), incrementalSync(checkpoint), webhook intake.

## Owned tables / topics / endpoints

None yet. Ownership is declared per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md) (tables) and [EventModel §3](../../docs/engineering/EventModel.md) (topics) as content lands in later phases; this charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.connectors.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
