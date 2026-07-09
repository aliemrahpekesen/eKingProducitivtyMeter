# TASK-0013 — R-CR Review (with CC-1 G4 R-CA approval) + product-vision scrutiny

- **Task:** [../tasks/TASK-0013.md](../tasks/TASK-0013.md) · product-first hero-metric slice · **change class CC-1** (first analytics `/api/v1` contract)
- **Branch:** `feature/TASK-0013-friction-summary` · **base:** `integration/SPRINT-00` · **commit reviewed:** `bc3ba59`
- **Reviewers:** R-CR (independent) + **R-CA** (CC-1 G4 — first analytics contract + metric-definition visibility) · **Date:** 2026-07-09
- **Method:** independent adversarial review — 5 fresh-context dimensions (build/test, data-path/determinism, API-contract/dashboard-readiness, anti-surveillance/vision, gates/DoD/DEBT), **every finding adversarially re-verified** (refute-first). 13 agents; 1 finding refuted. `./gradlew check` independently re-run (Testcontainers, `--rerun-tasks`, no cache). Author prose not taken as evidence. Cited docs: [DatabasePlan §2/§5/§8](../../docs/engineering/DatabasePlan.md), [BackendPlan §2.4/§10](../../docs/engineering/BackendPlan.md), [APIDesign §1.4/§5](../../docs/engineering/APIDesign.md), [SecurityModel](../../docs/architecture/SecurityModel.md), [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md), [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md), [Sprint01PlanningWorkshop](../../reviews/product-planning/Sprint01PlanningWorkshop.md).

## Overall verdict: **APPROVED** — 0 BLOCKER · 0 MAJOR · 3 MINOR · 4 NIT

The increment is **correct, deterministic, tenant-isolated, and anti-surveillance-compliant**. All 10 required verification points PASS. No BLOCKER and no MAJOR — merge-authorized. The MINORs (a missing FEAT-031 field and an unregistered observability-waiver reference) and the cheap NITs were remediated before merge (see **§Remediation**); one finding was refuted.

## Verification matrix — the 10 required points (all PASS)

| # | Point | Verdict | Evidence |
|---|---|---|---|
| V1 | `./gradlew check` green all modules | **PASS** | `BUILD SUCCESSFUL`; forced `:eip-app:test --rerun-tasks` — TenantApiIntegrationTest + FrictionScoreTest genuinely executed |
| V2 | Real DB-backed via `metric_definition` + `rm_team_flow_current` + `core.team`, RLS-bound | **PASS** | `FrictionSummaryService.summary()` runs both reads in one `jdbc.read` tx; RLS GUC bound on the tx connection; SQL quoted, no stubs |
| V3 | Tenant A cannot see tenant B friction/team data | **PASS** | App boots as `eip_app` **NOBYPASSRLS** (USAGE+SELECT only on core+analytics); test asserts A excludes B's "Ops" team; all 3 tables FORCE-RLS |
| V4 | Score documented, stable, no-clock/no-random, no AI/external | **PASS** | `FrictionScore.of` = pure arithmetic (`10·breaches + 6·review + 4·age_days`, cap 100); grep finds no clock/RNG/LLM/network; formula mirrored in `metric_definition.formula` |
| V5 | Team-level only; no individual/surveillance data | **PASS** | grep of DTOs/SQL/seed finds no member/user/assignee/person/email; grain `team`; `rm_team_flow_current`+`core.team` carry no individual attribution (Law 6 / NFR-071) |
| V6 | Dashboard-card-ready, sorted worst-first | **PASS** | `WORST_FIRST` = score desc → name → id (a deterministic **total** order, independent of DB row order); `teams[0]` is the top bottleneck |
| V7 | Fails closed without tenant; established problem+json | **PASS** | routes through `TenantScopedJdbc.read` → `require()` → `NoTenantBoundException` → 401 `application/problem+json`; never a silent empty summary |
| V8 | OpenAPI snapshot matches the implemented contract | **PASS** | `/api/v1/friction/summary` present; `FrictionSummaryView`/`FrictionMetricView`/`TeamFrictionView` schemas match the records (regenerated after adding `inputs`) |
| V9 | Demo seed story Platform high / Payments medium / Web low | **PASS** | seeder Platform(74) > Payments(30) > Web(10); exact scores asserted; `@Profile("demo")`, idempotent |
| V10 | Known gaps tracked as DEBT (real Friction Index, RBAC, observability) | **PASS (post-fix)** | DEBT-013 (real wait-time-decomposition compute → eip-analytics), DEBT-012 (RBAC/denied-path, names friction), **DEBT-009 extended** to name `/friction/summary` (observability) |

## Findings (all independently re-verified; 1 refuted)

### MINOR

- **MINOR-1 — FEAT-031 metric surface omits `inputs`.** `FrictionMetricView` exposed 7 of the 8 FEAT-031/FR-056 definition fields; the `inputs jsonb` column (present in `analytics.metric_definition`) was not surfaced. *Location:* [FrictionMetricView.java](../../backend/eip-app/src/main/java/com/eip/app/api/FrictionMetricView.java). → **Fixed**: added `inputs` (as a JSON `JsonNode`) to the view + `readDefinition` SELECT + demo/test seed. The first metric-definition contract is now FEAT-031-complete.
- **MINOR-2 — observability waiver not extended to name the new endpoint.** G6 is content-triggered by adding `/friction/summary`; [DEBT-009](../debt-register.md) named only `session`/`connectors`, so this third endpoint's deferral was technically unregistered (Law 9). *Location:* [work/debt-register.md](../debt-register.md). → **Fixed**: DEBT-009 origin + description extended to include TASK-0013 / `friction/summary`.
- **MINOR-3 — (same root as MINOR-1)** FR-056 `inputs` completeness under G7. → resolved by the MINOR-1 fix.

### NIT

- **NIT-1 — `capsAtMaxScore` asserted against the `MAX_SCORE` constant** (partly tautological). → **Fixed**: asserts literal `100`.
- **NIT-2 — `isDeterministicForTheSameInputs` (`of(x)==of(x)`) can never fail.** → **Replaced** with `roundsFractionalDayAge` (half-day age → `4·0.5 = 2.0` → rounds to 2), a meaningful behavior test.
- **NIT-3 — `DemoDataSeeder` has no test** (`@Profile("demo")`, coverage-excluded). → tracked under [DEBT-011](../debt-register.md) (seeder test).
- **NIT-4 — seeded `metric_definition.formula` wording** omitted rounding / used integer-division notation, diverging from the code for fractional-day ages. → **Fixed**: formula string now `round(min(100, … /86400.0))`.

### Refuted

- **`teams[]` unbounded with no top-N cap** — raised as NIT, **refuted**: the array is bounded by the tenant's team count (a small, RLS-scoped set), and this is a composed **summary** resource (embedded bounded array), not a queryable collection — the APIDesign §1.4 pagination envelope correctly does not apply. Confirmed by D3's contract-shape judgment.

## What was independently confirmed clean

- **RLS isolation is real** (NOBYPASSRLS `eip_app`, USAGE+SELECT only; all 3 read tables FORCE-RLS with the `tenant_isolation` policy; unset GUC errors → fails closed).
- **Determinism** — pure function, no clock/RNG/AI/network; documented formula matches code and the seeded registry row; expected scores (74/30/10/50) are independently computed and exactly asserted.
- **Anti-surveillance (Law 6 / NFR-071)** — no individual data anywhere in the endpoint, DTOs, SQL, or seed; grain is `team`.
- **SQL correctness** — INNER JOIN excludes orphan/soft-deleted teams; all read columns `NOT NULL`; score bounded to [0,100] (no overflow/NaN); output order deterministic despite no SQL `ORDER BY`.
- **Vision honesty** — the code + docs explicitly frame this as a **v1 placeholder** over the flow read model, not the full wait-time-decomposition Friction Index (DEBT-013); FEAT-031 "visible metric definitions" is genuinely surfaced from the registry.

## Gate status (CC-1)

| Gate | Status |
|---|---|
| G1 Build & Static | **PASS** — `check` green; OpenAPI committed |
| G2 Tests | **PASS** — RLS isolation + determinism + fail-closed + FrictionScore unit; coverage ratchets hold |
| G3 Security | **PASS** — tenant isolation proven under NOBYPASSRLS; anti-surveillance (team-grain) verified |
| **G4 Architecture (R-CA)** | **APPROVED** — first analytics `/api/v1` contract; metric-definition visibility complete (FEAT-031); deterministic-first honored; placeholder-vs-real-engine boundary registered (DEBT-013) |
| G6 Observability | **WAIVED** — [DEBT-009](../debt-register.md) (extended to name `/friction/summary`), R-OE → TASK-0012 |
| G7 Documentation | **PASS** — MODULE.md + task + sprint updated in-PR; metric definition FEAT-031-complete |
| G8 Review & Done | this review |

**DoD note:** expected metric values are independently computed and asserted (TestingStrategy §7 substance present) as inline assertions rather than a formal golden-dataset fixture — acceptable for a deterministic placeholder; the formal golden-dataset lands with the real analytics compute (DEBT-013).

## Verdict: **APPROVED** — cleared to merge into `integration/SPRINT-00`.
