# docs-lint

The **G7 documentation lint** for EIP. Implements the four checks from
[DocumentationStandards §4](../../engineering-operating-system/DocumentationStandards.md) (L1–L4) — the
[DocumentationQualityReview §5](../../docs/reviews/DocumentationQualityReview.md) recommendation-2 checks
— so the 30+ spec documents stay drift-free. Wired into CI as the `docs-lint` job
([../../.github/workflows/ci.yml](../../.github/workflows/ci.yml)); any violation fails the gate.

DEBT-007 closed out the three documented breadth gaps: `/work` is now in L1/L2 scope, L4 grew four
new context-aware sub-checks, and L3 grew a Roadmap reconciliation alongside FeatureCatalog's.

## Run

```bash
python3 scripts/docs-lint/docs_lint.py             # exit 0 = clean, exit 1 = violations printed
python3 scripts/docs-lint/test_docs_lint.py -v      # test suite (stdlib unittest, no deps)
```

No dependencies beyond Python 3 (stdlib `unittest` — no `pytest` is vendored for this repo).

## Checks

| Check | What it verifies |
|---|---|
| **L1 Link resolution** | Every relative markdown link resolves to an existing file; for in-repo markdown targets the `#anchor` heading exists (GitHub-slug). External `http(s)`/`mailto:` links are skipped; images are skipped. Fenced/inline code spans are stripped before matching, so a backtick-wrapped example like `` `[x](./missing.md)` `` in a review/task doc's prose is never mistaken for a live link. |
| **L2 ID-reference resolution** | Every `FR-`/`NFR-`/`FEAT-`/`AC-`/`UC-`/`ADR-NNN` id resolves to its definition home (PRD for FR/NFR, FeatureCatalog for FEAT, AcceptanceCriteria for AC, UseCases for UC, `docs/adr/` + ArchitectureOverview §7 for ADR). Within `/work`, additionally every `TASK-NNNN`/`SPRINT-NN`/`DEBT-NNN`/`RISK-NNN` id resolves under a **"defined includes planned"** model — see below. |
| **L3 Count reconciliation** | FeatureCatalog §1 `Total`, the P0+P1+P2 split, the §1.2 phase-count row, and the actual `FEAT-NNN` row count all agree; and Roadmap.md's §1 per-phase `Features` column (plus its sum) agrees with the actual `FEAT-NNN` ids enumerated under each phase's own `N.2 Feature scope` heading. |
| **L4 Canonical-value greps** | No stale variants of load-bearing values, each its own named sub-check (`L4-rls-guc`/`L4-dlq`/`L4-nfr`/`L4-locale`/`L4-pnpm`) — see below. |

### L4 sub-checks

| Sub-check | What it verifies |
|---|---|
| `L4-rls-guc` | The RLS GUC must be `app.tenant_id` — `eip.tenant_id` is forbidden only as a **GUC expression** (`current_setting`/`set_config`/`SET LOCAL`), never the bare token (`eip.*` is a legitimate OTel span-attribute namespace). |
| `L4-dlq` | DLQ topic naming is `<group>.dlq`; forbids the two-tier `<topic>.<group>.dlq` anti-pattern notation DocumentationQualityReview found. |
| `L4-nfr` | Best-effort spot-check (not exhaustive): a line that explicitly cites one of the five CLAUDE.md canonical NFR budgets by ID (`NFR-003`/`NFR-010`/`NFR-011`/`NFR-012`/`NFR-050`) must state that budget's canonical figure. Anchored on the NFR-ID token so it never flags the many *other* p95/p50 SLOs in the doc set (RAG retrieval, Kafka consumer lag, audit hash-chain lag, …) that legitimately use different numbers. NFR-003 accepts both the 100,000/h baseline and its own documented 3× burst (300,000). |
| `L4-locale` | Frontend/doc source locale must be `en-US`; forbids `en-GB` outright, and a bare `en` locale token on any line that is actually discussing locale. |
| `L4-pnpm` | Frontend package manager must be `pnpm`; forbids lowercase `npm`/`yarn` (word-boundary — the unrelated uppercase `NPM` build-artifact-type enum literal in DomainModel.md never matches), except inside an explicit "why not X" comparison sentence (a negation/contrast word on the same line — `not`/`instead of`/`unlike`/`never`/`rather than`/`no longer`/`isn't`/`aren't`/`without`). |

### `/work` id resolution — "defined includes planned"

`TASK-NNNN`/`SPRINT-NN`/`DEBT-NNN`/`RISK-NNN` ids are defined by: a `work/tasks/TASK-NNNN.md` file
existing, a `work/sprints/SPRINT-NN.md` file existing, a `| DEBT-NNN |` row in `work/debt-register.md`,
or a `| RISK-NNN |` row in `engineering-operating-system/RiskManagementPolicy.md` (where this repo's
RISK register actually lives — union'd with `work/risk-register.md` should that ever be created). An id
that doesn't resolve is **not** automatically dangling: `/work` prose constantly plans ahead (a
debt-register "Target" cell naming a task that doesn't exist yet, a review noting work "lands in
SPRINT-04"). It is forgiven when **both** hold:

1. **Numerically plausible** — at most (highest currently-defined id of that prefix + 50).
2. **Not cited in a past/completed context** — no word like `resolved`/`fixed`/`closed`/`delivered`/
   `shipped`/`landed`/`merged`/`completed`/`implemented` sits in the same clause (sentence-bounded, ±45
   characters). A struck-through id (`~~SPRINT-03~~`) is always forgiven — it is a superseded plan, not a
   claim the thing exists. `closed` excludes the compound term `fail-closed`.

Both conditions are required: a numerically wild id (`TASK-9999`) fails even worded as "next", and a
plausible id genuinely cited as already resolved (`fixed by TASK-0040`, where `TASK-0040` doesn't exist)
fails even though it's in range. The window is deliberately narrow (sentence boundary AND a small
character cap) so a past-tense word describing an unrelated item elsewhere in the same long
table-row/bullet never poisons an adjacent, unrelated forward reference — calibrated against this
repo's real `/work` prose; see `test_docs_lint.py::TestWorkIdFixtures` for the specific cases.

## Scope

Lints `/docs`, `/engineering-operating-system`, `/reviews`, `/program`, `/sprints`, `/work`, every
`MODULE.md`, and the root docs (`README.md`, `CONTRIBUTING.md`, `CLAUDE.md`). L4's canonical-value greps
stay scoped to `/docs` and `/engineering-operating-system` only, per DocumentationStandards §4.

## Exemptions

Files that legitimately quote a dangling-by-design id or a forbidden value **as a rule or example** are
exempt from the relevant check (see `L2_EXEMPT` / `L4_EXEMPT` in `docs_lint.py`): the lint-example ids in
`sprints/sprint-00/TaskSpecs.md`, the drift examples in `DocumentationStandards.md` and
`DocumentationQualityReview.md`, and this `scripts/docs-lint/` directory. Most `/work` lint-demo ids
(e.g. `` `FR-999` ``/`` `ADR-099` `` in `work/tasks/TASK-0008.md`) never need an explicit exemption entry:
L1/L2 strip fenced and inline code spans before matching, and those demo ids are themselves
backtick-wrapped in the source prose.

## Extending (R-DE owns the L4 grep list)

Per [DocumentationStandards §4](../../engineering-operating-system/DocumentationStandards.md), R-DE MUST
extend the relevant L4 forbidden-pattern list (`CANON_FORBIDDEN`/`DLQ_FORBIDDEN`/`NFR_BUDGET_CHECKS`/etc.
in `docs_lint.py`) whenever a new load-bearing canonical value is minted (a literal appearing in ≥ 2
documents). Extending a list is a CC-6 PR.
