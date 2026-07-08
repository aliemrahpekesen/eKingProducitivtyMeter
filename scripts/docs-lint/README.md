# docs-lint

The **G7 documentation lint** for EIP. Implements the four checks from
[DocumentationStandards §4](../../engineering-operating-system/DocumentationStandards.md) (L1–L4) — the
[DocumentationQualityReview §5](../../docs/reviews/DocumentationQualityReview.md) recommendation-2 checks
— so the 30+ spec documents stay drift-free. Wired into CI as the `docs-lint` job
([../../.github/workflows/ci.yml](../../.github/workflows/ci.yml)); any violation fails the gate.

## Run

```bash
python3 scripts/docs-lint/docs_lint.py     # exit 0 = clean, exit 1 = violations printed
```

No dependencies beyond Python 3.

## Checks

| Check | What it verifies |
|---|---|
| **L1 Link resolution** | Every relative markdown link resolves to an existing file; for in-repo markdown targets the `#anchor` heading exists (GitHub-slug). External `http(s)`/`mailto:` links are skipped; images are skipped. |
| **L2 ID-reference resolution** | Every `FR-`/`NFR-`/`FEAT-`/`AC-`/`UC-`/`ADR-NNN` id resolves to its definition home (PRD for FR/NFR, FeatureCatalog for FEAT, AcceptanceCriteria for AC, UseCases for UC, `docs/adr/` + ArchitectureOverview §7 for ADR). |
| **L3 Count reconciliation** | FeatureCatalog §1 `Total`, the P0+P1+P2 split, the §1.2 phase-count row, and the actual `FEAT-NNN` row count all agree. |
| **L4 Canonical-value greps** | No stale variants of load-bearing values. Context-aware: the RLS GUC must be `app.tenant_id` — `eip.tenant_id` is forbidden only as a **GUC expression** (`current_setting`/`set_config`/`SET LOCAL`), never the bare token (`eip.*` is a legitimate OTel span-attribute namespace). |

## Scope

Lints `/docs`, `/engineering-operating-system`, `/reviews`, `/program`, `/sprints`, every `MODULE.md`,
and the root docs (`README.md`, `CONTRIBUTING.md`, `CLAUDE.md`). `/work` operational records are out of
scope (they reference the in-flight task before its file exists, future sprints, and illustrative ids);
extending L1/L2 to `/work` with `TASK`/`SPRINT`/`DEBT`/`RISK` resolution is a documented future tightening.

## Exemptions

Files that legitimately quote a dangling-by-design id or a forbidden value **as a rule or example** are
exempt from the relevant check (see `L2_EXEMPT` / `L4_EXEMPT` in `docs_lint.py`): the lint-example ids in
`sprints/sprint-00/TaskSpecs.md`, the drift examples in `DocumentationStandards.md` and
`DocumentationQualityReview.md`, and this `scripts/docs-lint/` directory.

## Extending (R-DE owns the L4 grep list)

Per [DocumentationStandards §4](../../engineering-operating-system/DocumentationStandards.md), R-DE MUST
extend the L4 forbidden-pattern list (`CANON_FORBIDDEN` in `docs_lint.py`) whenever a new load-bearing
canonical value is minted (a literal appearing in ≥ 2 documents). Extending the list is a CC-6 PR.
