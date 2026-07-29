# Increment Strategy — the demoable vertical slice per sprint

How the program guarantees that every `SPRINT-NN` ends with a **working, reviewable, demoable increment** rather than an integration debt. This packages the vertical-slice principle ([PhaseImpl §4](../docs/implementation/PhaseBasedImplementationPlan.md)) and the feature-flag discipline ([DevelopmentLifecycle §6](../engineering-operating-system/DevelopmentLifecycle.md)) into a per-sprint contract; it defines no new requirement. It is the operational form of golden rule 2 ([MasterProgram §3](./MasterProgram.md)).

## 1. What "working increment" means

A sprint's increment is not "the backend for X is written." It is a **vertical slice through the UI, on the Docker Compose stack, exercising a real path end to end** — real auth, real data path, real audit, real trace. Normative properties (every sprint MUST satisfy all):

| Property | Requirement | Enforced by |
|---|---|---|
| **Vertical, not horizontal** | The slice runs UI → `/api/v1` → module → PostgreSQL/Kafka/etc. and back. If the UI slice is thin, it is still real. Backend-only is not an increment. | [PhaseImpl §4](../docs/implementation/PhaseBasedImplementationPlan.md); demo run at sprint review |
| **On Compose** | Demonstrable from one `make dev-up` on the Compose stack ([ArchitectureOverview §10](../docs/architecture/ArchitectureOverview.md)); no hand-wired environment. | `VERIFIED` = Compose smoke E1/E3/E7 green ([DevelopmentLifecycle §3](../engineering-operating-system/DevelopmentLifecycle.md)) |
| **Reviewable** | Merged to `main` behind flags where incomplete; every task passed its `G0–G8` gates. `main` is always releasable. | [QualityGatePolicy §5](../engineering-operating-system/QualityGatePolicy.md) |
| **Demoable** | The outcome appears in the phase demo script or a test run — "done but not demonstrable is not done" ([PhaseImpl §12](../docs/implementation/PhaseBasedImplementationPlan.md)). | Story-level DoD ([PhaseImpl §12](../docs/implementation/PhaseBasedImplementationPlan.md)); RG1 at phase close |
| **Observable** | New endpoints/consumers/jobs ship metrics, traces, structured logs (unobservable = unfinished). | `G6` ([QualityGatePolicy](../engineering-operating-system/QualityGatePolicy.md)); [`../CLAUDE.md`](../CLAUDE.md) law 8 |

## 2. Per-sprint increment — one-liner table (Phase 0)

`increment =` the smallest honest thing a reviewer can watch run at sprint review. Each Phase-0 increment is a growing portion of the single Phase-0 demo script ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md), [Roadmap §4.5](../docs/product/Roadmap.md)); `SPRINT-03` runs it end to end.

| Sprint | increment = | Demo-script pointer |
|---|---|---|
| **`SPRINT-00`** | `make dev-up` boots the full Compose stack healthy; CI is green on the empty modules; a PR that violates a Spring Modulith boundary is **blocked** by CI, then fixed and passes; the backfilled ADRs (ADR-001..020) are browsable under `/docs/adr`. | [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md) final step (CI blocks a Modulith violation, then passes); M0.1 (CI green on empty modules) |
| **`SPRINT-01`** | A first **RLS-proven, cross-tenant-blocked** write/read through `/api/v1`, with the OTel trace HTTP→DB visible in Grafana/Tempo, and the OpenAPI spec published. | [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md) (create tenant; trace of the request in Grafana/Tempo); milestone M0.3 (first RLS-proven write/read) + the "OTel traces from HTTP → DB visible" exit criterion |
| **`SPRINT-02`** | Log in via Keycloak with a tenant-scoped token → store a secret → rotate it → show the **masked** value, the **audit trail**, and the request **trace**. | [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md) (log in as Deniz; store a secret, rotate it; masked value + audit entries); milestones M0.2, M0.4 |
| **`SPRINT-03`** | The **full Phase-0 demo script, end to end through the UI**: clean clone → `make dev-up` → login → create tenant + invite `TENANT_ADMIN` → store/rotate secret → masked value, audit, trace → CI blocks then passes a Modulith violation. | [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md) in full; [Roadmap §4.5](../docs/product/Roadmap.md) Phase-0 demo milestone |

(Phase 1 increments follow the same rule against the Phase-1 demo script [PhaseImpl §6.2](../docs/implementation/PhaseBasedImplementationPlan.md); they are set at Phase-1 kickoff — see [ImplementationRoadmap §3](./ImplementationRoadmap.md).)

## 3. Feature-flag discipline (`eip.features.*`)

Work-in-progress that would break a vertical slice merges **behind an `eip.features.*` flag** so `main` stays releasable and demos toggle honestly ([DevelopmentLifecycle §6](../engineering-operating-system/DevelopmentLifecycle.md), [PhaseImpl §4](../docs/implementation/PhaseBasedImplementationPlan.md)). Rules (all normative, all from the EOS — restated only as pointers):

- A partial vertical MUST merge flag-off; the demo toggles the flag on for the slice that is real.
- Every live flag is tracked as a **DEBT** entry (flag debt) in `/work/debt-register.md` and MUST be removed within one phase of the feature's GA ([TechnicalDebtPolicy](../engineering-operating-system/TechnicalDebtPolicy.md)).
- A flag never hides a broken gate: flag-gated code still passes `G1–G8`. Flags manage *product* completeness, never *quality*.
- In Phase 0 flags are light (the platform is thin); they matter most where a story spans sprints — e.g. the admin console UI (`SPRINT-03`) surfacing capabilities whose backend landed in `SPRINT-02`.

## 4. How `SPRINT-00`…`SPRINT-03` compose toward v0.1

The four increments are cumulative — each is the previous slice plus one more real layer — so integration happens continuously, never at the end:

```
SPRINT-00  stack + CI + eip-core skeleton         (the ground)
   +       persistence + tenancy + trace + API     → SPRINT-01  (a tenant-isolated request you can trace)
   +       identity + authz + audit + secrets       → SPRINT-02  (a real login that stores an audited, rotated secret)
   +       UI shell + admin console                 → SPRINT-03  (the whole thing, driven from the browser) = v0.1
```

`v0.1` is the **internal milestone** cut when the Phase-0 exit criteria ([PRD §10 Phase 0](../docs/product/PRD.md)) pass and RG1/RG2/RG4 clear in the `SPRINT-03` dry-run ([ImplementationRoadmap §5](./ImplementationRoadmap.md)) — not customer GA, which is `v1.0` at Phase 5 ([Roadmap §10](../docs/product/Roadmap.md)).

## 5. No big-bang integration

The rule that makes the above safe: **integration is continuous, never deferred.** Concretely (normative):

1. Every sprint's increment runs on Compose at sprint review — a reality check that cannot be faked ([PhaseImpl §11](../docs/implementation/PhaseBasedImplementationPlan.md): phase demos run from `main` on a clean environment).
2. No sprint may end with a component that "will be wired up next sprint": if it is not in a running vertical slice, it is not done ([PhaseImpl §12](../docs/implementation/PhaseBasedImplementationPlan.md)).
3. Cross-lane dependencies are resolved by sequencing dependency-linked tasks ([ParallelizationPlan](./ParallelizationPlan.md)), not by a late merge of parallel branches — single-writer lanes mean there is no big divergent branch to reconcile.
4. `main` is always releasable ([QualityGatePolicy §5](../engineering-operating-system/QualityGatePolicy.md)); the release train ([Roadmap §10](../docs/product/Roadmap.md)) tags a version off a green `main`, it does not assemble one.

## Related documents

- [MasterProgram.md](./MasterProgram.md) — golden rule 2 (every sprint ships a slice)
- [ImplementationRoadmap.md](./ImplementationRoadmap.md) — the phase→version→sprint timeline the increments cut
- [DevelopmentSequence.md](./DevelopmentSequence.md) — the order the increments build in
- [SprintCatalog.md](./SprintCatalog.md) · [Sprint00.md](./Sprint00.md) · [Sprint01.md](./Sprint01.md) · [Sprint02.md](./Sprint02.md) · [Sprint03.md](./Sprint03.md) — the "Demo increment" field per sprint
- [`../docs/implementation/PhaseBasedImplementationPlan.md`](../docs/implementation/PhaseBasedImplementationPlan.md) §4 (vertical-slice principle), §5.3 (Phase-0 demo), §12 (story DoD)
- [`../engineering-operating-system/DevelopmentLifecycle.md`](../engineering-operating-system/DevelopmentLifecycle.md) §6 — feature flags and incomplete verticals
- [`../docs/product/Roadmap.md`](../docs/product/Roadmap.md) §4.5, §10 · [`../docs/product/PRD.md`](../docs/product/PRD.md) §10 — Phase-0 demo milestone and exit criteria
