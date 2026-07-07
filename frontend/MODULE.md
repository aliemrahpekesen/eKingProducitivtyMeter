# Module: frontend

- **Stack:** React 18 + TypeScript (strict) + Vite, pnpm (packageManager pinned)
- **Owner:** R-FA
- **State:** Phase-0 skeleton (Vite app + entry stub only — TASK-0001). The real app shell lands in P0-E5-S1 (SPRINT-03).

## Purpose

Single-page admin console and dashboards for EIP. Authoritative structure: [FrontendPlan §12](../docs/engineering/FrontendPlan.md); EOS additions in [RepositoryStructure §3](../engineering-operating-system/RepositoryStructure.md).

## Layout (target, filled in later phases)

`src/app` (shell, router, providers, auth) · `src/api/generated` (CI-generated OpenAPI client, committed, never hand-edited) · `src/design` (tokens, theme, EipChart, EipDataTable) · `src/features` · `src/forms` · `src/i18n` (source locale `en-US`) · `src/test` · `e2e`. This scaffold ships `src/app/App.tsx` + `src/main.tsx` only.

## Invariants

- `strict: true`; no `any`, no `@ts-ignore` ([CodingStandards §4](../engineering-operating-system/CodingStandards.md)).
- API types come only from the generated OpenAPI client; server state lives in TanStack Query only; cross-feature imports only via `/design`, `/api`, `/app`, `/forms` ([FrontendPlan §12](../docs/engineering/FrontendPlan.md)).
- `pnpm` is the only package manager; the lockfile is committed ([CodingStandards §8](../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Per [FrontendPlan §13](../docs/engineering/FrontendPlan.md) / [RepositoryStructure §2.1](../engineering-operating-system/RepositoryStructure.md): shell + auth + tenant switcher (Phase 0), integration UIs (Phase 1), dashboards (Phase 2), AI/report UIs (Phase 3–4), hardening (Phase 5).
