# ADR-022: Four environment profiles (dev/test/preprod/prod) with `demo` as a seeding feature profile

- **Status:** Accepted
- **Approver:** Founder direction, ratified R-CA (2026-07-12, TASK-0017)
- **Affected anchors/modules:** [BackendPlan §2.3](../engineering/BackendPlan.md), [LocalInstall runbook](../infrastructure/LocalInstall.md); `eip-app` configuration, install tooling; CC-7.

## Context

BackendPlan §2.3 prescribed exactly three runtime profiles (`local`, `demo`, `prod`). Operating the
platform through its lifecycle needs the conventional four-environment ladder — `dev`, `test`,
`preprod`, `prod` — each manageable through a separate config file, and an installer that selects
an environment end-to-end (infra + backend + frontend). Meanwhile `demo` had grown into two mixed
meanings: "an environment" and "seed the deterministic simulation dataset".

## Decision

Adopt four **environment profiles**, one config file each (`application-dev.yaml`,
`application-test.yaml`, `application-preprod.yaml`, `application-prod.yaml`), plus `demo` retained
strictly as a **seeding feature profile** (gates `DemoDataSeeder` only) that the `dev` and `test`
environments activate via Spring profile groups. `preprod`/`prod` never seed. `prod` keeps the
fail-fast `ProductionTenantResolutionGuard` until OIDC lands (DEBT-012): its configuration is
complete and validated, but boot is refused so header/demo tenant resolution can never serve
production traffic. Worker role selection still uses additional profiles (BackendPlan §9), never a
fifth environment profile. Environment selection for operators lives in `config/environments/*.env`
consumed by `scripts/install/install.{sh,ps1}`.

## Consequences

- **Positive:** conventional environment ladder; seeding is explicit and composable; per-env
  config diffable in one file each; the installer's `--env` flag maps 1:1 to Spring profiles and
  Vite modes; prod hardening stays a config-complete, boot-blocked contract until OIDC.
- **Negative:** `local` disappears as a profile name (dev replaces it); docs referencing `local`
  needed reconciliation; the dev→demo group means dev always seeds (a fresh non-seeded dev boot
  requires `SPRING_PROFILES_ACTIVE=dev` minus the group — accepted, test envs behave the same).

## Alternatives rejected

- **Keep `local`/`demo`/`prod` and overload them** — rejected: no test/preprod rungs, and `demo`'s
  double meaning kept leaking into scripts and tests.
- **Make `demo` a config property instead of a profile** — rejected: `@Profile("demo")` already
  gates the seeder bean cleanly and the existing demo runner + tests depend on it; groups give the
  same ergonomics without churn.
