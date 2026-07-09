# Module: frontend

- **Stack:** React 18 + TypeScript (strict) + Vite + TanStack Query, pnpm (packageManager pinned). Prettier + ESLint + Vitest for quality.
- **Owner:** R-FA
- **State:** P0-E5-S1 — first customer-visible slice (TASK-0014): tenant-aware session view, paginated connector list, and the Engineering Friction dashboard card over the live `/api/v1` read surface. Auth/OIDC, design tokens, routing, and i18n follow in SPRINT-02+.

## Purpose

Single-page admin console and dashboards for EIP. Authoritative structure: [FrontendPlan §12](../docs/engineering/FrontendPlan.md); EOS additions in [RepositoryStructure §3](../engineering-operating-system/RepositoryStructure.md).

## Layout (current)

- `src/api` — `types.ts` (hand-written to mirror the committed OpenAPI contract exactly — the CI-generated `src/api/generated` client is deferred, [DEBT-016](../work/debt-register.md)), `client.ts` (fetch + `X-EIP-Tenant` + problem+json), `hooks.ts` (TanStack Query).
- `src/app` — shell (`App.tsx`), TanStack Query + dev tenant providers (`TenantProvider`/`tenantContext`).
- `src/components` — `SessionCard`, `ConnectorList` (cursor-paginated), `FrictionCard` (hero + metric explainer), `DemoBanner`/`DemoBadge`, `states`, `TenantBar`.
- `src/lib`, `src/test`, `src/styles.css`. Target additions (`src/design`, `src/features`, `src/forms`, `src/i18n`, `e2e`) land in later phases per FrontendPlan §12.

## Demo / honesty

- **One-command demo:** `make demo-up` starts Postgres + backend (`demo` profile) + this frontend with the **fixed demo tenant** (`DemoDataSeeder.DEMO_TENANT_ID`) preloaded via `VITE_EIP_TENANT_ID` — no manual DB tenant lookup. Open `http://localhost:5173`. See `frontend/.env.example` for the env template (copy to `.env.development` for a manual `pnpm dev`).
- The entire current backend serves **seeded simulation data** — a persistent `DemoBanner` says so app-wide, and each connector with `simulation = true` carries a `SIMULATION` badge. No real Jira/Bitbucket/Sonar ingestion is implied.
- **Team-level only** (NFR-071): nothing renders or ranks individual developers.

## Invariants

- `strict: true`; no `any`, no `@ts-ignore` ([CodingStandards §4](../engineering-operating-system/CodingStandards.md)).
- API types mirror the committed OpenAPI contract and add no field the contract does not define; server state lives in TanStack Query only ([FrontendPlan §12](../docs/engineering/FrontendPlan.md)). The CI-generated OpenAPI client replaces the hand-written types — [DEBT-016](../work/debt-register.md).
- `pnpm` is the only package manager; the lockfile is committed ([CodingStandards §8](../engineering-operating-system/CodingStandards.md)).
- Formatting is Prettier (`format:check` in CI, G1); no auth/tenant trust beyond the dev `X-EIP-Tenant` header (replaced by OIDC in SPRINT-02).

## Content roadmap

Per [FrontendPlan §13](../docs/engineering/FrontendPlan.md) / [RepositoryStructure §2.1](../engineering-operating-system/RepositoryStructure.md): shell + auth + tenant switcher (Phase 0), integration UIs (Phase 1), dashboards (Phase 2), AI/report UIs (Phase 3–4), hardening (Phase 5).
