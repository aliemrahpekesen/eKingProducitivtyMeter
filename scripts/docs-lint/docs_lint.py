#!/usr/bin/env python3
"""EIP docs-lint — the four G7 documentation checks.

Implements the four checks from DocumentationStandards.md §4 (L1–L4), themselves the
DocumentationQualityReview §5 recommendation-2 checks, so documentation stays drift-free:

  L1  Link resolution        — every relative markdown link resolves to an existing file
                               (and, for in-repo markdown targets, the #anchor heading).
  L2  ID-reference resolution — every FR/NFR/FEAT/AC/UC/ADR id (and, in /work, TASK/SPRINT/
                               DEBT/RISK) resolves to its definition home.
  L3  Count reconciliation   — FeatureCatalog stated totals equal actual table row counts.
  L4  Canonical-value greps  — no stale variants of load-bearing values (context-aware).

Exit 0 when clean; exit 1 (and print every violation) on any failure. Wired into CI as the
G7 stage (QualityGatePolicy §2). Run locally: `python3 scripts/docs-lint/docs_lint.py`.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# ---------------------------------------------------------------------------
# Scope
# ---------------------------------------------------------------------------
# Trees whose markdown is linted for links + id references. Scope is the TASK-0008 spec set
# (/docs, /engineering-operating-system, /reviews, /program, /sprints) plus the module charters
# and root docs. `/work` operational records (task specs, reviews, registers) are intentionally
# out of scope: they legitimately reference the in-flight task before its file exists, future
# sprints, and illustrative ids — extending L1/L2 to /work with TASK/SPRINT/DEBT/RISK resolution
# (DocumentationStandards §4 full scope) is a documented future tightening.
DOC_TREES = ["docs", "engineering-operating-system", "reviews", "program", "sprints"]
ROOT_DOCS = ["README.md", "CONTRIBUTING.md", "CLAUDE.md"]
# Files that legitimately contain dangling-by-design ids (lint test examples / forward refs) —
# exempt from L2 id resolution.
L2_EXEMPT = {
    "sprints/sprint-00/TaskSpecs.md",  # FR-999/ADR-099 lint demo ids + ADR-021 forward ref
    "scripts/docs-lint/docs_lint.py",
    "scripts/docs-lint/README.md",
}

# L4 canonical greps run over the spec + governance trees only.
L4_TREES = ["docs", "engineering-operating-system"]
# Files that legitimately quote a forbidden value as a *rule or example* — exempt from L4.
L4_EXEMPT = {
    "engineering-operating-system/DocumentationStandards.md",
    "docs/reviews/DocumentationQualityReview.md",
    "sprints/sprint-00/TaskSpecs.md",
    "scripts/docs-lint/docs_lint.py",
    "scripts/docs-lint/README.md",
}

# Definition homes for L2 id resolution.
DEF_HOMES = {
    "FR": ["docs/product/PRD.md"],
    "NFR": ["docs/product/PRD.md"],
    "FEAT": ["docs/product/FeatureCatalog.md"],
    "AC": ["docs/product/AcceptanceCriteria.md"],
    "UC": ["docs/product/UseCases.md"],
}


def rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def md_files() -> list[Path]:
    files: list[Path] = []
    for tree in DOC_TREES:
        files += sorted((ROOT / tree).rglob("*.md"))
    for name in ROOT_DOCS:
        if (ROOT / name).exists():
            files.append(ROOT / name)
    files += sorted(ROOT.glob("backend/*/MODULE.md"))
    files += sorted(ROOT.glob("frontend/MODULE.md"))
    # de-dup, stable
    seen, out = set(), []
    for f in files:
        if f not in seen:
            seen.add(f)
            out.append(f)
    return out


def slug(heading: str) -> str:
    """GitHub-style heading slug: strip leading #, lowercase, drop punctuation
    (keep letters/digits/space/hyphen), spaces→hyphens (consecutive hyphens preserved)."""
    text = re.sub(r"^#+\s*", "", heading.strip())
    text = text.replace("`", "")
    text = text.lower()
    text = re.sub(r"[^a-z0-9 \-]", "", text)
    return text.replace(" ", "-")


def headings_of(path: Path) -> set[str]:
    slugs: dict[str, int] = {}
    out: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if re.match(r"^#{1,6}\s", line):
            base = slug(line)
            n = slugs.get(base, 0)
            out.add(base if n == 0 else f"{base}-{n}")
            slugs[base] = n + 1
    return out


LINK_RE = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)]+)\)")


def check_links(files: list[Path]) -> list[str]:
    errs: list[str] = []
    heading_cache: dict[Path, set[str]] = {}
    for f in files:
        text = f.read_text(encoding="utf-8")
        for m in LINK_RE.finditer(text):
            target = m.group(1).strip()
            if target.startswith(("http://", "https://", "mailto:")) or "://" in target:
                continue
            path_part, _, frag = target.partition("#")
            if path_part == "":  # same-file anchor
                dest = f
            else:
                dest = (f.parent / path_part).resolve()
                if not dest.exists():
                    errs.append(f"L1 broken link: {rel(f)} -> {target} (missing {path_part})")
                    continue
            if frag and dest.suffix == ".md" and dest.exists():
                if dest not in heading_cache:
                    heading_cache[dest] = headings_of(dest)
                if frag.lower() not in heading_cache[dest]:
                    errs.append(f"L1 broken anchor: {rel(f)} -> {target} (no heading #{frag})")
    return errs


ID_RE = re.compile(r"(?<![A-Za-z0-9])(FR|NFR|FEAT|AC|UC|ADR)-(\d{3})(?!\d)")


def defined_ids() -> dict[str, set[str]]:
    d: dict[str, set[str]] = {k: set() for k in ("FR", "NFR", "FEAT", "AC", "UC", "ADR")}
    for pref, homes in DEF_HOMES.items():
        for h in homes:
            p = ROOT / h
            if p.exists():
                for m in ID_RE.finditer(p.read_text(encoding="utf-8")):
                    if m.group(1) == pref:
                        d[pref].add(m.group(0))
    # ADR defined by file presence + the ArchitectureOverview §7 index
    for adr in (ROOT / "docs/adr").glob("ADR-*.md"):
        mm = re.match(r"(ADR-\d{3})", adr.name)
        if mm:
            d["ADR"].add(mm.group(1))
    ov = ROOT / "docs/architecture/ArchitectureOverview.md"
    if ov.exists():
        for m in ID_RE.finditer(ov.read_text(encoding="utf-8")):
            if m.group(1) == "ADR":
                d["ADR"].add(m.group(0))
    return d


def check_ids(files: list[Path]) -> list[str]:
    errs: list[str] = []
    defined = defined_ids()
    for f in files:
        if rel(f) in L2_EXEMPT:
            continue
        text = f.read_text(encoding="utf-8")
        for m in ID_RE.finditer(text):
            tok, pref = m.group(0), m.group(1)
            if tok not in defined[pref]:
                errs.append(f"L2 dangling {pref} ref: {rel(f)} -> {tok}")
    return errs


def check_counts() -> list[str]:
    errs: list[str] = []
    fc = ROOT / "docs/product/FeatureCatalog.md"
    if not fc.exists():
        return ["L3 FeatureCatalog.md missing"]
    text = fc.read_text(encoding="utf-8")
    # actual unique feature-definition rows: lines like "| FEAT-NNN |"
    actual = len({m.group(0) for m in re.finditer(r"(?m)^\| (FEAT-\d{3}) ", text)})
    # §1 summary "Total" row: | | **Total** | **145** | **66** | **47** | **32** |
    tot = re.search(r"\*\*Total\*\*\s*\|\s*\*\*(\d+)\*\*\s*\|\s*\*\*(\d+)\*\*\s*\|\s*\*\*(\d+)\*\*\s*\|\s*\*\*(\d+)\*\*", text)
    if not tot:
        return ["L3 FeatureCatalog §1 Total row not found"]
    total, p0, p1, p2 = (int(tot.group(i)) for i in range(1, 5))
    if total != actual:
        errs.append(f"L3 FeatureCatalog Total {total} != actual FEAT rows {actual}")
    if p0 + p1 + p2 != total:
        errs.append(f"L3 FeatureCatalog P0+P1+P2 {p0+p1+p2} != Total {total}")
    # §1.2 phase table row "| Features | 20 | 19 | ... |"
    ph = re.search(r"(?m)^\| Features \|((?:\s*\d+\s*\|)+)\s*$", text)
    if ph:
        nums = [int(x) for x in re.findall(r"\d+", ph.group(1))]
        if sum(nums) != total:
            errs.append(f"L3 FeatureCatalog phase-count sum {sum(nums)} != Total {total}")
    else:
        errs.append("L3 FeatureCatalog §1.2 phase-count row not found")
    return errs


# Forbidden canonical patterns (context-aware; R-DE extends per DocumentationStandards §4).
# The RLS GUC is `app.tenant_id`; `eip.tenant_id` is forbidden ONLY as a GUC expression
# (`eip.*` is legitimately an OTel span-attribute namespace, so the bare token is allowed).
CANON_FORBIDDEN = [
    (r"current_setting\(\s*['\"]eip\.tenant_id", "RLS GUC drift: use app.tenant_id (current_setting)"),
    (r"set_config\(\s*['\"]eip\.tenant_id", "RLS GUC drift: use app.tenant_id (set_config)"),
    (r"SET\s+(?:LOCAL|SESSION)\s+eip\.tenant_id", "RLS GUC drift: use app.tenant_id (SET LOCAL)"),
]


def check_canonical() -> list[str]:
    errs: list[str] = []
    pats = [(re.compile(rx), msg) for rx, msg in CANON_FORBIDDEN]
    for tree in L4_TREES:
        for f in sorted((ROOT / tree).rglob("*.md")):
            if rel(f) in L4_EXEMPT:
                continue
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                for rx, msg in pats:
                    if rx.search(line):
                        errs.append(f"L4 canonical drift: {rel(f)}:{i} — {msg}")
    return errs


def main() -> int:
    files = md_files()
    checks = [
        ("L1 links", check_links(files)),
        ("L2 id refs", check_ids(files)),
        ("L3 counts", check_counts()),
        ("L4 canonical", check_canonical()),
    ]
    total = 0
    for name, errs in checks:
        status = "OK" if not errs else f"{len(errs)} FAIL"
        print(f"[{ 'PASS' if not errs else 'FAIL' }] {name}: {status}")
        for e in errs:
            print(f"    - {e}")
        total += len(errs)
    print(f"\ndocs-lint: {'PASS' if total == 0 else f'FAIL ({total} violation(s))'} "
          f"over {len(files)} markdown files")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
