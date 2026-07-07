# ADR-009: Drive connector configuration from JSON Schema

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [ConnectorFramework](../engineering/ConnectorFramework.md) (Connector SPI, config contract); `eip-connectors`; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Every connector needs its configuration validated, rendered as an admin UI form, and documented. Maintaining three hand-written artifacts per connector across the full connector catalogue would drift.

## Decision

Each connector declares its configuration as a JSON Schema; server-side validation, schema-driven UI form generation, and configuration documentation all derive from that single schema.

## Consequences

- **Positive:** one declarative contract drives validation, UI, and docs consistently for every connector; adding a connector means writing one schema, not three synchronized artifacts.
- **Negative:** configuration evolution must be connector-versioned so existing instances keep validating; very complex or conditional configurations stretch JSON Schema's expressiveness and occasionally need custom validators.

## Alternatives rejected

- **Bespoke config classes and UI per connector** — rejected: triples the per-connector work and lets validation, UI, and docs drift out of sync.
- **Free-form key/value configuration** — rejected: no structural validation and no way to generate the admin form, pushing errors to runtime.
