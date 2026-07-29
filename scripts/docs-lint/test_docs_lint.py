#!/usr/bin/env python3
"""Test suite for docs_lint.py (DEBT-007 close-out).

Stdlib `unittest` only (no third-party test runner is vendored for this repo's Python
tooling — see README.md). Two kinds of coverage, per file/class:

  * Fixture tests (`TestWorkIdFixtures`, `TestRoadmapFixture`, `TestL4Fixtures`,
    `TestCodeSpanFixtures`) build a minimal synthetic repo tree under a TemporaryDirectory
    and call the check functions with that tree as `root=`, so a *known, injected* violation
    is proven caught, and a known-good analogue of the same shape is proven NOT flagged —
    without writing any fixture content into the real repo tree (write-set: scripts/docs-lint/**
    only).
  * Real-repo tests (`TestRealRepo`) run the same functions against this repo's actual
    `/work` and `/docs` trees (root=REPO_ROOT) and assert they come back clean — proving the
    heuristics are calibrated against real prose, not just synthetic fixtures. The one
    documented exception is `TestRealRepo.test_l1_links_have_exactly_the_known_drift`, which
    locks in the single genuine pre-existing broken link this task discovered (a review doc
    citing FrictionMetricView.java at its pre-TASK-0016-remediation path) — see the task's
    final report. If this test ever needs updating, that's either the drift being fixed
    (delete the assertion) or a NEW drift appearing (investigate before touching the test).

Run: `python3 scripts/docs-lint/test_docs_lint.py -v`
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import docs_lint as dl  # noqa: E402

REPO_ROOT = dl.ROOT


def write(root: Path, rel_path: str, content: str) -> Path:
    p = root / rel_path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
    return p


class TestRealRepo(unittest.TestCase):
    """Runs the extended checks against THIS repo's real /docs and /work trees."""

    def test_work_id_resolution_is_clean(self):
        files = dl.md_files(REPO_ROOT)
        work_files = [f for f in files if dl.rel(f, REPO_ROOT).startswith("work/")]
        errs = dl.check_work_ids(work_files, REPO_ROOT)
        self.assertEqual(errs, [], f"unexpected dangling work ids: {errs}")

    def test_roadmap_counts_reconcile(self):
        errs = dl.check_roadmap_counts(REPO_ROOT)
        self.assertEqual(errs, [], f"Roadmap count drift: {errs}")

    def test_l4_dlq_clean(self):
        self.assertEqual(dl.check_dlq(REPO_ROOT), [])

    def test_l4_nfr_clean(self):
        self.assertEqual(dl.check_nfr(REPO_ROOT), [])

    def test_l4_locale_clean(self):
        self.assertEqual(dl.check_locale(REPO_ROOT), [])

    def test_l4_pnpm_clean(self):
        self.assertEqual(dl.check_pnpm(REPO_ROOT), [])

    def test_l4_rls_guc_still_clean(self):
        """Pre-existing check must still pass — extending L4 must not regress it."""
        self.assertEqual(dl.check_canonical(REPO_ROOT), [])

    def test_l2_id_refs_still_clean_with_work_in_scope(self):
        """Bringing /work into md_files() scope must not introduce FR/NFR/etc false
        positives (the lint-demo ids in TASK-0008.md / SPRINT-00.md are backtick-wrapped and
        stripped by strip_code_spans() before ID_RE ever sees them)."""
        files = dl.md_files(REPO_ROOT)
        self.assertEqual(dl.check_ids(files, REPO_ROOT), [])

    def test_l1_links_have_exactly_the_known_drift(self):
        """Documented, honest finding (see task report): work/reviews/TASK-0013-review.md
        cites FrictionMetricView.java at its pre-remediation path
        (backend/eip-app/.../api/FrictionMetricView.java); the TASK-0016 architecture
        remediation moved friction compute/read-side into eip-analytics
        (backend/eip-analytics/.../api/FrictionMetricView.java), and this historical review
        record was never repointed. Out of this task's write-set (work/reviews/** is
        documentation content, not scripts/docs-lint/**) — left as an honest FAIL, not
        silenced. If a future task fixes the link, this assertion should shrink to []."""
        files = dl.md_files(REPO_ROOT)
        errs = dl.check_links(files, REPO_ROOT)
        self.assertEqual(
            errs,
            [
                "L1 broken link: work/reviews/TASK-0013-review.md -> "
                "../../backend/eip-app/src/main/java/com/eip/app/api/FrictionMetricView.java "
                "(missing ../../backend/eip-app/src/main/java/com/eip/app/api/FrictionMetricView.java)"
            ],
        )


class WorkFixtureBase(unittest.TestCase):
    """Builds a minimal synthetic /work + EOS tree: TASK-0001..0002, SPRINT-01, one DEBT row,
    one RISK row — so ceilings are TASK<=52, SPRINT<=51, DEBT<=51, RISK<=51 (margin 50)."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        write(self.root, "work/tasks/TASK-0001.md", "# TASK-0001\n")
        write(self.root, "work/tasks/TASK-0002.md", "# TASK-0002\n")
        write(self.root, "work/sprints/SPRINT-01.md", "# SPRINT-01\n")
        write(
            self.root,
            "work/debt-register.md",
            "# Technical Debt Register\n\n"
            "| ID | Origin | Description | Risk | Size | Target | Owner |\n"
            "|---|---|---|---|---|---|---|\n"
            "| DEBT-001 | TASK-0001 | desc | risk | S | TASK-0002 | R-BA |\n",
        )
        write(
            self.root,
            "engineering-operating-system/RiskManagementPolicy.md",
            "# Risk Management Policy\n\n"
            "| ID | Description | L | I | Score | Owner | Mitigation | Review |\n"
            "|---|---|---|---|---|---|---|---|\n"
            "| RISK-001 | desc | 1 | 1 | 1 | R-CA | mitigation | review |\n",
        )

    def tearDown(self):
        self._tmp.cleanup()

    def run_check(self, target_rel: str, content: str) -> list[str]:
        write(self.root, target_rel, content)
        files = dl.md_files(self.root)
        work_files = [f for f in files if dl.rel(f, self.root).startswith("work/")]
        return dl.check_work_ids(work_files, self.root)


class TestWorkIdFixtures(WorkFixtureBase):
    def test_defined_id_never_flagged(self):
        errs = self.run_check(
            "work/tasks/TASK-0003.md",
            "# TASK-0003\n\nSee TASK-0001 and DEBT-001 and RISK-001 and SPRINT-01.\n",
        )
        self.assertEqual(errs, [])

    def test_plausible_forward_reference_is_forgiven(self):
        """A not-yet-created TASK id, well within the +50 margin, in a forward-looking
        'Target' sentence — must NOT be flagged (the debt-register's real 'Target' column
        does this constantly)."""
        errs = self.run_check(
            "work/tasks/TASK-0003.md",
            "# TASK-0003\n\nTarget: the next task, TASK-0030, will pick up the residual.\n",
        )
        self.assertEqual(errs, [])

    def test_strikethrough_superseded_target_is_forgiven(self):
        """Mirrors the real debt-register DEBT-004 row: '~~SPRINT-03~~ **Done** in TASK-0002.'
        — a struck-through original plan is not a dangling reference even though 'Done'
        sits right next to it."""
        errs = self.run_check(
            "work/tasks/TASK-0003.md",
            "# TASK-0003\n\n~~SPRINT-05~~ **Done** in TASK-0002.\n",
        )
        self.assertEqual(errs, [])

    def test_past_tense_undefined_reference_fails(self):
        """A genuinely dangling reference: an id that does not exist, cited as already
        resolved. Must fail even though TASK-0040 is numerically within the margin."""
        errs = self.run_check(
            "work/tasks/TASK-0003.md",
            "# TASK-0003\n\nThis bug was fixed by TASK-0040 last sprint.\n",
        )
        self.assertEqual(len(errs), 1)
        self.assertIn("TASK-0040", errs[0])

    def test_past_tense_marker_far_away_in_same_row_does_not_poison_unrelated_id(self):
        """Regression for the real false-positive found calibrating this heuristic
        (work/tasks/TASK-0010.md): a past-tense word describing a DIFFERENT, unrelated item
        later in the same long sentence/row must not poison an earlier forward reference."""
        errs = self.run_check(
            "work/tasks/TASK-0003.md",
            "# TASK-0003\n\n"
            "Endpoint hardening lands in SPRINT-04. NIT-1 fixed (unrelated cleanup item).\n",
        )
        self.assertEqual(errs, [])

    def test_implausibly_high_id_fails_even_with_future_wording(self):
        """Numeric plausibility is a hard ceiling: a 'next'-worded id far beyond the +50
        margin is still dangling — both conditions (plausible AND future-context) are
        required, not either alone."""
        errs = self.run_check(
            "work/tasks/TASK-0003.md",
            "# TASK-0003\n\nTarget: next up is TASK-9999, a distant future task.\n",
        )
        self.assertEqual(len(errs), 1)
        self.assertIn("TASK-9999", errs[0])

    def test_undefined_debt_and_risk_follow_same_rules(self):
        errs_forward = self.run_check(
            "work/tasks/TASK-0003.md",
            "# TASK-0003\n\nTarget phase: file DEBT-030 and RISK-030 if this recurs.\n",
        )
        self.assertEqual(errs_forward, [])
        errs_past = self.run_check(
            "work/tasks/TASK-0004.md",
            "# TASK-0004\n\nDEBT-030 was resolved and RISK-030 was closed after review.\n",
        )
        # both DEBT-030 and RISK-030 are cited in a resolved/closed past context
        self.assertEqual(len(errs_past), 2)


class TestRoadmapFixture(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def _roadmap(self, phase0_count: int, phase0_ids: str) -> str:
        return (
            "# EIP Roadmap\n\n"
            "## 1. Timeline overview\n\n"
            "| Phase | Version | Indicative duration | Headline | Features (see `./FeatureCatalog.md`) |\n"
            "|-------|---------|---------------------|----------|--------------------------------------|\n"
            f"| 0 | v0.1 | 2 months | Foundations | {phase0_count} |\n"
            "| 1 | v0.2 | 2 months | Ingestion | 2 |\n\n"
            "## 4. Phase 0 – Foundations (v0.1)\n\n"
            "### 4.2 Feature scope\n"
            f"{phase0_ids}.\n\n"
            "### 4.3 Deliverables\n- stuff\n\n"
            "## 5. Phase 1 – Ingestion (v0.2)\n\n"
            "### 5.2 Feature scope\n"
            "FEAT-010, FEAT-011.\n\n"
            "### 5.3 Deliverables\n- stuff\n"
        )

    def test_matching_counts_pass(self):
        write(self.root, "docs/product/Roadmap.md", self._roadmap(3, "FEAT-001, FEAT-002, FEAT-003"))
        self.assertEqual(dl.check_roadmap_counts(self.root), [])

    def test_stated_total_higher_than_actual_fails(self):
        """Injected gap: header says 3 but only 2 ids are actually enumerated — the genuine
        drift shape DEBT-007 item 3 asks docs-lint to catch."""
        write(self.root, "docs/product/Roadmap.md", self._roadmap(3, "FEAT-001, FEAT-002"))
        errs = dl.check_roadmap_counts(self.root)
        self.assertTrue(any("Phase 0" in e and "3" in e and "2" in e for e in errs), errs)

    def test_missing_roadmap_reported(self):
        self.assertEqual(dl.check_roadmap_counts(self.root), ["L3 Roadmap.md missing"])


class TestL4Fixtures(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    # --- L4-dlq ---
    def test_dlq_anti_pattern_caught(self):
        write(self.root, "docs/engineering/Fixture.md", "DLQ naming is `<topic>.<group>.dlq` here.\n")
        errs = dl.check_dlq(self.root)
        self.assertEqual(len(errs), 1)
        self.assertIn("<topic>.<group>.dlq", errs[0])

    def test_dlq_correct_pattern_not_flagged(self):
        write(self.root, "docs/engineering/Fixture.md", "DLQ naming is `<group>.dlq`, e.g. `eip.analytics.flow-metrics.dlq`.\n")
        self.assertEqual(dl.check_dlq(self.root), [])

    # --- L4-nfr ---
    def test_nfr_wrong_api_latency_caught(self):
        write(self.root, "docs/engineering/Fixture.md", "Per NFR-011, non-analytical endpoints target p95 < 500 ms.\n")
        errs = dl.check_nfr(self.root)
        self.assertEqual(len(errs), 1)
        self.assertIn("NFR-011", errs[0])

    def test_nfr_correct_api_latency_not_flagged(self):
        write(self.root, "docs/engineering/Fixture.md", "Per NFR-011, non-analytical endpoints target p95 < 300 ms.\n")
        self.assertEqual(dl.check_nfr(self.root), [])

    def test_nfr_burst_figure_not_a_false_positive(self):
        """NFR-003's own canonical requirement includes the 3x burst figure (300,000) — must
        not be flagged as a contradiction of the 100,000 baseline (the real repo has exactly
        this shape in TestingStrategy.md §11)."""
        write(
            self.root,
            "docs/engineering/Fixture.md",
            "Ingestion burst: 300,000 events/hour for 15 minutes (NFR-003).\n",
        )
        self.assertEqual(dl.check_nfr(self.root), [])

    def test_nfr_unrelated_p95_without_id_not_flagged(self):
        """A p95 SLO for a different subsystem that never cites an NFR id is out of scope —
        this check only anchors on explicit NFR-ID citations."""
        write(self.root, "docs/engineering/Fixture.md", "RAG retrieval p95 <= 900 ms with re-rank.\n")
        self.assertEqual(dl.check_nfr(self.root), [])

    # --- L4-locale ---
    def test_locale_en_gb_caught(self):
        write(self.root, "docs/engineering/Fixture.md", "Frontend locale is en-GB by default.\n")
        errs = dl.check_locale(self.root)
        self.assertEqual(len(errs), 1)
        self.assertIn("en-GB", errs[0])

    def test_locale_bare_en_caught(self):
        write(self.root, "docs/engineering/Fixture.md", "The source locale is en for all strings.\n")
        errs = dl.check_locale(self.root)
        self.assertEqual(len(errs), 1)

    def test_locale_en_us_not_flagged(self):
        write(self.root, "docs/engineering/Fixture.md", "The source locale is en-US for all strings.\n")
        self.assertEqual(dl.check_locale(self.root), [])

    # --- L4-pnpm ---
    def test_pnpm_bare_npm_caught(self):
        write(self.root, "docs/engineering/Fixture.md", "Install frontend deps with npm install.\n")
        errs = dl.check_pnpm(self.root)
        self.assertEqual(len(errs), 1)

    def test_pnpm_negated_comparison_not_flagged(self):
        write(self.root, "docs/engineering/Fixture.md", "CI installs with --frozen-lockfile; npm/yarn are never used.\n")
        self.assertEqual(dl.check_pnpm(self.root), [])

    def test_pnpm_uppercase_enum_literal_not_flagged(self):
        """Regression for the real false-positive found calibrating this heuristic
        (docs/architecture/DomainModel.md): the artifact-type enum literal `NPM` is an
        unrelated build-artifact kind, not the frontend package manager."""
        write(self.root, "docs/engineering/Fixture.md", "type enum CONTAINER_IMAGE|JAR|NPM|GENERIC.\n")
        self.assertEqual(dl.check_pnpm(self.root), [])


class TestCodeSpanFixtures(unittest.TestCase):
    """L1/L2 must not treat backtick-wrapped example syntax as a live link or id reference —
    mirrors the real TASK-0008.md / TASK-0008-review.md / TASK-0006-review.md demo text this
    task found once /work came into L1/L2 scope."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_backticked_broken_link_example_not_flagged(self):
        write(self.root, "work/reviews/Fixture-review.md", "probe `[x](./missing.md)` -> L1 FAIL, exit 1.\n")
        files = dl.md_files(self.root)
        self.assertEqual(dl.check_links(files, self.root), [])

    def test_real_broken_link_still_flagged(self):
        write(self.root, "work/reviews/Fixture-review.md", "See [x](./missing.md) for detail.\n")
        files = dl.md_files(self.root)
        errs = dl.check_links(files, self.root)
        self.assertEqual(len(errs), 1)

    def test_backticked_dangling_id_example_not_flagged(self):
        write(
            self.root,
            "docs/product/PRD.md",
            "| ID | Category | Requirement and measurable target |\n| --- | --- | --- |\n",
        )
        write(self.root, "work/tasks/Fixture.md", "A PR adding a dangling `FR-999` reference is blocked.\n")
        files = dl.md_files(self.root)
        self.assertEqual(dl.check_ids(files, self.root), [])

    def test_real_dangling_id_still_flagged(self):
        write(
            self.root,
            "docs/product/PRD.md",
            "| ID | Category | Requirement and measurable target |\n| --- | --- | --- |\n",
        )
        write(self.root, "work/tasks/Fixture.md", "See FR-999 for the requirement.\n")
        files = dl.md_files(self.root)
        errs = dl.check_ids(files, self.root)
        self.assertEqual(len(errs), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
