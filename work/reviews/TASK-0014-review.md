# TASK-0014 — R-CR Review (frontend shell / P0-E5-S1, product-demo scrutiny)

- **Task:** [../tasks/TASK-0014.md](../tasks/TASK-0014.md) · story P0-E5-S1 · **change class CC-7** (frontend; contract-consuming only, no backend/contract change)
- **Branch:** `feature/TASK-frontend-shell-visible-slice` · **base:** `integration/SPRINT-00` · **commit reviewed:** `57d8af3`
- **Reviewer:** R-CR (independent) · **Date:** 2026-07-09
- **Method:** independent adversarial review — 5 fresh-context dimensions (build/gates, contract fidelity, demo honesty, TanStack Query/quality, gates/DEBT/scope), **every finding adversarially re-verified** (refute-first). 10 agents; 2 findings refuted (incl. a MAJOR). The build dimension independently ran all six frontend gates + `./gradlew check`. Cited: [CodeReviewChecklist](../../engineering-operating-system/CodeReviewChecklist.md), [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md), [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md), the committed contract `backend/eip-app/openapi/eip-openapi-v1.json`.

## Overall verdict: **APPROVED** — 0 BLOCKER · 0 MAJOR · 1 MINOR · 2 NIT

**32 of 33 checks PASS.** The shell consumes only contract-defined fields (no invented API fields), renders all three required surfaces correctly, labels simulation data honestly, implements loading/error/empty states throughout, and keeps the backend untouched. The MINOR/NIT items were remediated before this verdict was finalized.

## The 10 required verification points

| # | Point | Verdict |
|---|---|---|
| 1 | UI consumes only contract fields; no invented API fields | **PASS** — `types.ts` matches the OpenAPI schemas field-for-field; components read only those fields; no `lastSync`/`checkpoint` column (the contract has none) |
| 2 | Three surfaces render: session · paginated connectors · Friction card | **PASS** — session (tenantId+org, null-safe), connectors (`useInfiniteQuery` cursor/limit + `nextCursor`/`hasMore` + Load more), friction (worst-first `teams[0]` headline + breakdown + metric definition) |
| 3 | Demo/simulation clearly labeled, not real ingestion | **PASS (after remediation)** — persistent `DemoBanner`, per-connector `SIMULATION` badge, `SIMULATION DATA` chip on the friction hero; the one latent overclaim (a green "LIVE" badge) was removed |
| 4 | TanStack Query loading/error/empty states usable | **PASS** — loading/error(problem+json+traceId, 401→no-tenant)/empty for all three; no-tenant handled (queries disabled until a tenant is set); no blank/crash paths |
| 5 | Frontend gates pass (install-frozen/format/lint/typecheck/test/build) | **PASS** — independently re-run: all six green; 13 tests, 4 files; build 87 modules |
| 6 | Backend unchanged; `./gradlew check` passes | **PASS** — `BUILD SUCCESSFUL`; diff touches no `.java/.sql/.kts/openapi` (frontend + `.github` + `work/` only) |
| 7 | CI frontend steps wired; DEBT-004 truly resolved | **PASS** — `format:check` + `test` steps added to the `frontend` job; `eslint-config-prettier` present; Prettier now enforced in CI → DEBT-004 genuinely closed |
| 8 | DEBT-016 accurately recorded (generated-client / type-drift) | **PASS** — description/risk/owner accurate; genuinely open |
| 9 | Local demo instructions executable + honest (incl. tenant-id step) | **PASS (after remediation)** — the executable runbook (random tenant-id fetch + curl/URLs) was added to the task file |
| 10 | No backend endpoints, real connectors, AI, OIDC, RBAC, or unrelated scope | **PASS** — grep confirms none; only CI-quality wiring + work docs outside `/frontend` |

## Findings (all re-verified; 2 refuted)

### MINOR (remediated)

- **MINOR-1 — demo runbook missing from the artifact.** The task file/MODULE.md described the demo framing but did not tell an operator how to run it (the demo tenant id is random per seed — `DemoDataSeeder` uses `UUID.randomUUID()`, not logged — so it must be fetched via `SELECT id FROM core.tenant WHERE slug='demo'`). *Location:* [work/tasks/TASK-0014.md](../tasks/TASK-0014.md). → **Fixed**: added a "Local demo runbook" section with the fetch step + curl/URLs (Law 10 — knowledge in artifacts).

### NIT (remediated)

- **NIT-1 — 401→no-tenant mapping is an inference.** The OpenAPI snapshot documents only 200s, so `states.tsx` mapping `status===401` to "no tenant" is behavior-derived, not contract-derived. The backend genuinely returns 401 problem+json (verified: `ApiExceptionHandler`), so the mapping is correct; the contract just doesn't document errors yet ([DEBT-011](../debt-register.md)). → **Fixed**: added a clarifying comment tying it to DEBT-011.
- **NIT-2 — "Validation GREEN" wording (Law 3).** The state log implied gate evidence, but the frontend CI steps are new and have not run on the runner (branch not pushed). → **Fixed**: clarified it is local validation pending the CI run.

### Refuted (no action — but one drove a defensive fix)

- **MAJOR → REFUTED — green "LIVE" badge asserts real ingestion.** Refuted as *unreachable in the shipped slice*: the only `core.connector` INSERT (`DemoDataSeeder`) hardcodes `simulation=true`, and the slice exposes only GET endpoints, so `GET /api/v1/connectors` can never return `simulation=false` — the "LIVE" branch never executes. **However**, the verifier flagged a real latent wrinkle (the DB column defaults `simulation NOT NULL DEFAULT false`), and the same issue surfaced as the `no-overclaim` **CONCERN**. Under the product-demo honesty lens, I **remediated defensively**: the non-simulation cell now renders a neutral "—" marker (never "LIVE"/"connected"), so no future data path can make the UI overclaim real ingestion. `.badge-live` CSS removed.
- **MINOR → REFUTED — stale cross-tenant data after clearing the tenant.** Refuted: every query key embeds `tenantId` (`['session', tenantId]`, …), so changing tenant changes the cache key — no other tenant's data can render.

## Independently confirmed clean

- **Contract fidelity** — `types.ts` mirrors all six schemas exactly (names/types/nullability, incl. `inputs: JsonNode → unknown`, int64 `oldestInProgressAgeSec → number`); no component reads a non-contract property.
- **Null-safety** — `organizationName`, `nextCursor`, `metric` all guarded; `teams=[]` yields an empty state, not a crash on `teams[0]`.
- **Anti-surveillance (NFR-071)** — grep for individual/member/person/user/assignee/email rendering: none. Team-level only.
- **Quality** — TS strict (no `any`/`@ts-ignore`); stable query keys + `enabled` guard + `retry:false`; cursor pagination honors `hasMore`/`nextCursor` with no loop; no `dangerouslySetInnerHTML`; tenant from a dev header only.
- **Scope** — no backend endpoints, real connectors, AI, OIDC, or RBAC; the only non-frontend changes are the CI-quality wiring (`ci.yml`/README) + work docs.

## Gate status (CC-7)

| Gate | Status |
|---|---|
| G1 Build & Static | **PASS** — Prettier/ESLint/tsc/build green |
| G2 Tests | **PASS** — Vitest 13/13 (non-tautological: worst-first headline, caveats, simulation badge, states) |
| G7 Documentation | **PASS** — MODULE.md + task updated in-PR; DEBT-004 resolved, DEBT-016 filed |
| G8 Review & Done | this review |
| G4 | n/a — no contract anchor touched (contract-consuming only) |

## Can it merge?

**Yes — cleared to merge into `integration/SPRINT-00`** (0 BLOCKER, 0 MAJOR; MINOR/NITs remediated; all gates green). **Not merged and not pushed**, per instruction — the merge is a human/normal-flow step, and note the base-branch protection (ruleset `18708118`) requires 1 approving + code-owner review before the eventual `integration → base` PR (#1) can land.

## Next demo-hardening recommendation

Assessed against the four options — **recommend (A): fixed demo tenant + one-command demo run.**

- **(A) Fixed demo tenant + one-command demo — RECOMMENDED (do next).** The review's only MINOR and both CONCERNs trace back to demo friction: the tenant id is random per seed, unlogged, and must be hand-fetched from the DB. A small backend change (a **fixed, well-known demo tenant UUID** in `DemoDataSeeder`, and/or logging the seeded id on startup) plus a one-command runup makes the executive demo frictionless and removes the "paste a UUID from psql" step. Smallest change, highest demo value, low risk. This is the right next task.
- **(B) Generated OpenAPI frontend client — do soon (pays DEBT-016).** Real value (removes hand-written-type drift risk), but the hand-written types are verified-correct against the committed snapshot and the surface is only 3 endpoints, so drift risk is currently low. Best sequenced with the OpenAPI diff gate (DEBT-011). Second priority.
- **(C) Real connector ingestion — NOT next.** This is the major product frontier (Connector SPI + ingestion → canonical → correlation), not "hardening." It should follow a frictionless demo path and a connector-admin write surface, and it dwarfs the other options in size/risk.
- **(D) Frontend visual polish — lowest priority.** The `exec-friendly` check already PASSED; the UI is clean and executive-ready. Polish is low marginal value now.

**Sequence:** A (demo tenant + one-command) → B (generated client + OpenAPI diff gate) → later C (ingestion). D only opportunistically.
