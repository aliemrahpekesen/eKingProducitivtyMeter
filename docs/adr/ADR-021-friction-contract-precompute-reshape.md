# ADR-021: Accept a pre-1.0 breaking reshape of the Engineering Friction API contract

- **Status:** Accepted
- **Approver:** R-CA (2026-07-10, TASK-0016 architecture/data review)
- **Affected anchors/modules:** [APIDesign §3](../engineering/APIDesign.md) (additive-only within v1), [FrictionCatalog / FeatureCatalog FEAT-031](../product/FeatureCatalog.md); `eip-app` (`/api/v1/friction/*`), `frontend`; CC-1.

## Context

TASK-0016 replaced the seed-only Engineering Friction placeholder with a real computed metric (v0.1). The prior `GET /api/v1/friction/summary` `TeamFrictionView` exposed four **flow-signal placeholder** fields — `wip`, `wipLimitBreaches`, `oldestInProgressAgeSec`, `reviewQueueDepth` — that were never a real metric (DEBT-013) and are meaningless against a historical, all-resolved dataset (they compute to zero). The real metric is a cycle-time decomposition, whose natural response fields (dominant cause, component percentages, work-item count, per-component seconds, version, computed-at) are a different shape.

[APIDesign §3](../engineering/APIDesign.md) permits only **additive** changes within `/api/v1`; a breaking change normally requires a `/api/v2` side-by-side with `Deprecation`/`Sunset` headers. Removing the four placeholder fields is, by that rule, a breaking change — and the OpenAPI additive-only diff gate that would flag it is not yet wired (DEBT-011), so an independent review caught it rather than CI.

## Decision

Accept the reshape as a **one-time pre-1.0 baseline reset** of the friction contract, without a v2 side-by-side. Rationale: `/api/v1` is pre-GA with no external consumers (no production profile exists — DEBT-012; the dev tenant resolves via a header, not OIDC); the removed fields were an explicitly-registered placeholder (DEBT-013), not a committed metric; and the sole in-repo consumer (`frontend/src/api/types.ts` + `FrictionCard.tsx`) was updated in lockstep in the same change. The committed OpenAPI snapshot (`backend/eip-app/openapi/eip-openapi-v1.json`) is regenerated as the new baseline. When the OpenAPI diff gate lands (DEBT-011), this commit is its baseline; the additive-only rule applies strictly from here.

## Consequences

- **Positive:** the friction contract reflects the real computed metric (dominant cause + component breakdown + version + evidence drill-down) instead of dead placeholder fields; no dead v2 surface to maintain pre-release.
- **Negative:** one documented exception to APIDesign §3's additive-only rule while pre-GA; any future field removal must follow the v2 side-by-side process. The exception is recorded here so it is not precedent for post-GA changes.

## Alternatives rejected

- **Keep the four placeholder fields and map computed data onto them** — rejected: the labels (WIP, breaches, oldest age, review queue) do not match the computed decomposition, so the values would mislead (dishonest metric, violates FEAT-031 transparency).
- **Add the computed fields additively and leave the placeholders zeroed** — rejected: ships permanently-zero, meaningless fields on the hero card and preserves the DEBT-013 placeholder in the contract indefinitely.
- **Introduce `/api/v2/friction` side-by-side now** — rejected: premature for a pre-GA surface with no external consumers; doubles the surface to maintain for no consumer benefit.
