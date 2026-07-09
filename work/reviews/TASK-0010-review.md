# TASK-0010 — R-CR Review (with CC-1 G4 R-CA approval)

- **Task:** [../tasks/TASK-0010.md](../tasks/TASK-0010.md) · story P0-E3-S1 (+ P0-E4-S2 slice) · **change class CC-1** (tenant-context propagation + first `/api/v1` contract anchor)
- **Branch:** `feature/TASK-0010-tenant-api` · **base:** `integration/SPRINT-00` · **commit reviewed:** `e9685fc`
- **Reviewers:** R-CR (independent) + **R-CA** (CC-1 G4 gate owner — first `/api/v1` contract anchor) · **Date:** 2026-07-09
- **Method:** independent adversarial review — 5 fresh-context reviewer dimensions (build/test, tenant-context mechanism, API/data-path, scope-guardrails, gates/DoD/vision), **every finding adversarially re-verified by a second independent agent** (refute-first). 18 agents; 0 findings refuted. `./gradlew check` independently re-run (no cache). Author prose not taken as evidence. Cited docs: [DatabasePlan §5/§12](../../docs/engineering/DatabasePlan.md), [BackendPlan §2.4/§10](../../docs/engineering/BackendPlan.md), [APIDesign §1.4/§1.5/§5/§7](../../docs/engineering/APIDesign.md), [SecurityModel](../../docs/architecture/SecurityModel.md), [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md), [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md).

## Overall verdict (initial): **CHANGES REQUESTED**

**0 BLOCKER · 2 MAJOR · 8 MINOR · 3 NIT.** The core of the increment is **correct and proven**: tenant isolation is enforced through the HTTP surface against real PostgreSQL 16 under a `NOBYPASSRLS` role, the tenant-context filter binds/clears safely, the RLS GUC is bound on the same transaction connection the query runs on, and reads come from the real DB — no stubbed data, no forbidden connector/AI/UI/prod-auth/`/docs` shortcuts. **However**, two MAJOR findings block a clean merge: the first `/api/v1` list endpoint violates the mandatory pagination-envelope contract (a CC-1 anchor precedent that would be a breaking change to fix later), and the two new endpoints ship with no observability and no G6 waiver (Law 8/9).

> **Resolution:** the author (R-IE) remediated both MAJORs and the contract-shape MINORs in follow-up commits; deferrable items were registered as DEBT via the documented waiver path. See **§Remediation** and the **final verdict** at the end. Merge occurred only after re-verification returned 0 MAJOR.

## Verification matrix — the 9 required points (all PASS)

| # | Point | Verdict | Evidence |
|---|---|---|---|
| V1 | `./gradlew check` green across all modules | **PASS** | `BUILD SUCCESSFUL`; forced `--rerun-tasks` on `:eip-app:test :eip-tenancy:test` + coverage-verification = 28 tasks executed, 0 failures |
| V2 | Filter binds + clears context per request | **PASS** | [TenantContextFilter.java:37-42](../../backend/eip-app/src/main/java/com/eip/app/tenant/TenantContextFilter.java) — `set` on resolve, `clear()` in `finally` (runs even on throw); `TenantContextHolder.clear()` → `CURRENT.remove()` (no thread-pool leak) |
| V3 | `HeaderTenantResolver` dev-only, behind `TenantResolver` seam | **PASS** | Javadoc "MUST NOT be trusted in production"; filter depends on the interface, not the concrete class → SPRINT-02 OIDC swap is one bean. (Hardened in remediation with `@Profile("!prod")`.) |
| V4 | `session`/`connectors` read the real DB, not stubbed | **PASS** | `SELECT … FROM core.organization` / `core.connector` via `TenantScopedJdbc.read` (RLS-bound tx) — no hardcoded data |
| V5 | Missing tenant fails closed with RFC 7807 problem+json | **PASS** | `require()` throws `NoTenantBoundException` → `ApiExceptionHandler` → 401 `application/problem+json`, title "Tenant required" |
| V6 | Tenant A cannot see Tenant B's connectors through the API | **PASS** | Integration test: app connects as `eip_app` **NOBYPASSRLS**; A returns only "Jira A", B only "Bitbucket B" (RLS-off would return 2). Genuine end-to-end RLS proof |
| V7 | OpenAPI v1 snapshot matches implemented endpoints/DTOs | **PASS** | Both paths + `SessionView`/`ConnectorView` schemas match the records field-for-field (regenerated after remediation to reflect the `PageView` envelope) |
| V8 | No forbidden shortcuts | **PASS** | grep: no HTTP client / Jira/Bitbucket/Sonar sync; no LLM/AI; no frontend; demo data is inert `simulation=true` rows, `@Profile("demo")`, idempotent; **0 files under `/docs` touched** |
| V9 | Genuinely first customer-visible `/api/v1` surface, vision-aligned | **PASS** | Deterministic (real DB reads, no probabilistic path), on-prem (no external calls), team-level (no individual metrics — Law 6). First callable contract |

## Findings (all independently re-verified; 0 refuted)

### MAJOR

- **MAJOR-1 — Observability gate (G6 / Law 8) unmet and unwaived.** The two new `/api/v1` handlers ship with **no Micrometer `eip_*` metrics, no OTel spans, no structured JSON logs**, and no `traceId` on the problem+json body. [QualityGatePolicy line 119](../../engineering-operating-system/QualityGatePolicy.md) makes G6 **content-triggered** ("adds an endpoint … content-based, not class-based"); [line 81] permits deferral **only via an R-OE-granted DEBT waiver**, which did not exist. Unregistered shortcut → violates Law 8 ("unobservable = unfinished") and Law 9 ("register or fix"). *Location:* [ConnectorController.java](../../backend/eip-app/src/main/java/com/eip/app/api/ConnectorController.java), [SessionController.java](../../backend/eip-app/src/main/java/com/eip/app/api/SessionController.java).
  → **Resolved:** [DEBT-009](../debt-register.md) — R-OE G6 waiver, evidence deferred to **TASK-0012** (this sprint's observability lane), which also delivers the `traceId` MDC.

- **MAJOR-2 — Contract-anchor deviation: bare array instead of the pagination envelope.** `GET /api/v1/connectors` returned `List<ConnectorView>` (a bare JSON array). [APIDesign §1.4 line 10](../../docs/engineering/APIDesign.md) mandates list responses be `{ items, nextCursor, hasMore }` with `cursor`/`limit`/`sort` params; [line 54] "**all** list endpoints support cursor/limit/sort". As the **first** `/api/v1` list endpoint under a **CC-1 contract anchor**, a bare array sets a wrong precedent and is a **breaking** change to correct later (array→object is not additive). *Location:* [ConnectorController.java:31](../../backend/eip-app/src/main/java/com/eip/app/api/ConnectorController.java).
  → **Resolved:** introduced `PageView<T>` envelope + real keyset pagination (`limit` default 50/max 200, opaque `cursor` over `(name, id)`, `hasMore`). See §Remediation.

### MINOR

- **MINOR-1 — problem+json omits `traceId`.** [BackendPlan §10 line 244](../../docs/engineering/BackendPlan.md): "every problem+json response carries `traceId`". `traceId` derives from the OTel/`traceparent` MDC that observability wiring populates. → deferred with **DEBT-009** (observability, TASK-0012).
- **MINOR-2 — error `type` URI uses `urn:eip:error:*` not `/problems/*`.** [BackendPlan §10 line 235] maps 401 → `/problems/unauthenticated`. → **Fixed** in remediation (`/problems/unauthenticated`).
- **MINOR-3 — `HeaderTenantResolver` is an unconditional `@Component`** (active in every profile), so the unauthenticated header-trusting resolver has no structural guard against becoming the prod resolver. → **Fixed**: `@Profile("!prod")` (structurally excluded from prod; OIDC resolver replaces it in SPRINT-02).
- **MINOR-4 — committed OpenAPI snapshot is not CI-verified** against the generated contract (export is opt-in via `EIP_OPENAPI_EXPORT`); it can silently drift. → the additive-only OpenAPI diff gate is **TASK-0011**; registered as **DEBT-011**.
- **MINOR-5 — OpenAPI contract documents only 200s**, not the 401/400 problem+json error shapes. → **DEBT-011** (OpenAPI hardening, TASK-0011).
- **MINOR-6 — `DemoDataSeeder` (AC5) has no test** and is coverage-excluded via `**/config/**`. → **DEBT-011**-adjacent; registered under **DEBT-012**.
- **MINOR-7 — no RBAC permission / denied-path test per endpoint** ([DefinitionOfDone line 45](../../engineering-operating-system/DefinitionOfDone.md); Law 5). Spring Security is not yet on the classpath (SPRINT-02 OIDC/RBAC). → registered as **DEBT-012** (SPRINT-02).
- **MINOR-8 — cursor pagination lacks `sort` param + signed/expiring cursors** per APIDesign §1.4 (post-remediation state — the envelope is correct; hardening deferred). → **DEBT-010** (pagination hardening, TASK-0011).

### NIT

- **NIT-1 — isolation test seeded one connector per tenant** (`length()==1` is a weaker discriminator). → **Strengthened** in remediation (multiple connectors per tenant + a pagination test).
- **NIT-2 — OpenAPI 200 uses `*/*` media** (springdoc default, not a mismatch). → subsumed by DEBT-011.
- **NIT-3 — two changed files outside the declared write-set** (`work/sprints/SPRINT-01.md`, `eip-tenancy/MODULE.md`). Both are **process-required** (sprint-state tracking; RepositoryStructure §6 inv.4 charter update in the same PR). → the task write-set note was under-specified; **corrected** in the task file. Not a defect.

## What was NOT wrong (independently confirmed clean)

- **RLS isolation is real, not simulated by a superuser.** The test creates `eip_app … NOBYPASSRLS` with only `USAGE`/`SELECT`, seeds separately as the container superuser, then queries as `eip_app`. The isolation proof holds.
- **`TenantScopedJdbc` binds the GUC on the correct connection.** `@Transactional(readOnly=true)` → `DataSourceUtils.getConnection` returns the tx-bound connection; `set_config('app.tenant_id', ?, true)` is transaction-local; the `JdbcClient` re-resolves the same tx connection at execute time. No cross-connection isolation gap.
- **Fail-closed everywhere.** `require()` throws on unbound; the RLS policy has no default GUC (missed bind errors, never silent-empty) — defense in depth.
- **No forbidden shortcuts.** No real connector sync, no AI, no UI, no `/docs` drift, no prod-auth trust in a default/prod profile (post-`@Profile("!prod")`).

## Gate status (CC-1)

| Gate | Status |
|---|---|
| G1 Build & Static | **PASS** — `check` green; OpenAPI committed |
| G2 Tests | **PASS** — RLS-through-API isolation + fail-closed + pagination + holder unit; coverage ratchets hold |
| G3 Security | **PASS** — tenant isolation proven under NOBYPASSRLS; dev resolver `@Profile("!prod")`-guarded; fail-closed |
| **G4 Architecture (R-CA)** | **APPROVED (post-remediation)** — first `/api/v1` contract anchor now conforms to APIDesign §1.4 (pagination envelope) + §10 (`/problems/*`); tenant-context propagation is the permanent mechanism |
| G6 Observability | **WAIVED** — [DEBT-009](../debt-register.md), R-OE, → TASK-0012 |
| G7 Documentation | **PASS** — MODULE.md charters (eip-app, eip-tenancy) updated in-PR |
| G8 Review & Done | this review |

## Final verdict (post-remediation): **APPROVED** — 0 BLOCKER · 0 MAJOR

Both MAJORs remediated (pagination envelope shipped; G6 waived via DEBT-009); contract-shape MINORs fixed (`/problems/*`, `@Profile("!prod")`); remaining MINOR/NIT items are registered as DEBT-010/011/012 or fixed. Re-verification: `./gradlew check` green, OpenAPI snapshot regenerated to the `PageView` envelope, isolation + pagination tests green. Cleared to merge into `integration/SPRINT-00`.
