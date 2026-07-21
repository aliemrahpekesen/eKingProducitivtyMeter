#!/usr/bin/env python3
"""EIP docs-lint — the four G7 documentation checks.

Implements the four checks from DocumentationStandards.md §4 (L1–L4), themselves the
DocumentationQualityReview §5 recommendation-2 checks, so documentation stays drift-free:

  L1  Link resolution        — every relative markdown link resolves to an existing file
                               (and, for in-repo markdown targets, the #anchor heading).
                               Scope includes `/work` (DEBT-007 item 1).
  L2  ID-reference resolution — every FR/NFR/FEAT/AC/UC/ADR id resolves to its definition
                               home; within `/work`, additionally every TASK/SPRINT/DEBT/RISK
                               id resolves under a "defined includes planned" model
                               (DEBT-007 item 1 — see WORK_ID_MARGIN / classify_work_id below).
  L3  Count reconciliation   — FeatureCatalog stated totals equal actual table row counts,
                               and (DEBT-007 item 3) Roadmap per-phase Features counts equal
                               the actual FEAT-ids enumerated in each phase's own scope list.
  L4  Canonical-value greps  — no stale variants of load-bearing values (context-aware):
                               RLS GUC (L4-rls-guc, original), DLQ naming (L4-dlq), NFR budget
                               figures (L4-nfr), frontend locale (L4-locale), and package
                               manager (L4-pnpm) — DEBT-007 item 2.

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
# (/docs, /engineering-operating-system, /reviews, /program, /sprints) plus the module charters,
# root docs, and (DEBT-007 item 1) `/work` — sprints, tasks, handoffs, debt-register,
# risk-register, reviews, approvals, demo-evidence, releases.
DOC_TREES = ["docs", "engineering-operating-system", "reviews", "program", "sprints", "work"]
ROOT_DOCS = ["README.md", "CONTRIBUTING.md", "CLAUDE.md"]
# Files that legitimately contain dangling-by-design ids (lint test examples / forward refs) —
# exempt from L2 id resolution. NOTE: most of the ids these files quote are themselves
# backtick-wrapped (e.g. `FR-999`, `ADR-099`), so strip_code_spans() (below) already removes
# them from consideration before ID_RE ever runs; these entries are a belt-and-suspenders
# fallback for any future non-backticked demo id landing in the same files.
L2_EXEMPT = {
    "sprints/sprint-00/TaskSpecs.md",  # FR-999/ADR-099 lint demo ids + ADR-021 forward ref
    "scripts/docs-lint/docs_lint.py",
    "scripts/docs-lint/README.md",
}

# L4 canonical greps run over the spec + governance trees only (DocumentationStandards §4
# scope column for L4 is /docs, /engineering-operating-system — not extended to /work).
L4_TREES = ["docs", "engineering-operating-system"]
# Files that legitimately quote a forbidden value as a *rule or example* — exempt from L4.
L4_EXEMPT = {
    "engineering-operating-system/DocumentationStandards.md",
    "docs/reviews/DocumentationQualityReview.md",
    "sprints/sprint-00/TaskSpecs.md",
    "scripts/docs-lint/docs_lint.py",
    "scripts/docs-lint/README.md",
}

# Definition homes for L2 id resolution (FR/NFR/FEAT/AC/UC/ADR — unchanged).
DEF_HOMES = {
    "FR": ["docs/product/PRD.md"],
    "NFR": ["docs/product/PRD.md"],
    "FEAT": ["docs/product/FeatureCatalog.md"],
    "AC": ["docs/product/AcceptanceCriteria.md"],
    "UC": ["docs/product/UseCases.md"],
}


def rel(p: Path, root: Path = ROOT) -> str:
    return p.relative_to(root).as_posix()


def md_files(root: Path = ROOT) -> list[Path]:
    files: list[Path] = []
    for tree in DOC_TREES:
        files += sorted((root / tree).rglob("*.md"))
    for name in ROOT_DOCS:
        if (root / name).exists():
            files.append(root / name)
    files += sorted(root.glob("backend/*/MODULE.md"))
    files += sorted(root.glob("frontend/MODULE.md"))
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


# ---------------------------------------------------------------------------
# Shared helper: strip fenced/inline code spans before hunting for link or id syntax in
# PROSE. Real markdown renderers never treat a backtick-wrapped `[x](y)` or `FR-999` as a
# live link/reference — only L1/L2 need this (L4's canonical-value greps intentionally keep
# scanning raw text, since the load-bearing literals they check are themselves normally
# written as inline code, e.g. `<group>.dlq`).
# ---------------------------------------------------------------------------
_FENCE_RE = re.compile(r"```.*?```", re.S)
_INLINE_CODE_RE = re.compile(r"`[^`\n]*?`")


def strip_code_spans(text: str) -> str:
    text = _FENCE_RE.sub(lambda m: " " * len(m.group(0)), text)
    text = _INLINE_CODE_RE.sub(lambda m: " " * len(m.group(0)), text)
    return text


LINK_RE = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)]+)\)")


def check_links(files: list[Path], root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    heading_cache: dict[Path, set[str]] = {}
    for f in files:
        text = strip_code_spans(f.read_text(encoding="utf-8"))
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
                    errs.append(f"L1 broken link: {rel(f, root)} -> {target} (missing {path_part})")
                    continue
            if frag and dest.suffix == ".md" and dest.exists():
                if dest not in heading_cache:
                    heading_cache[dest] = headings_of(dest)
                if frag.lower() not in heading_cache[dest]:
                    errs.append(f"L1 broken anchor: {rel(f, root)} -> {target} (no heading #{frag})")
    return errs


ID_RE = re.compile(r"(?<![A-Za-z0-9])(FR|NFR|FEAT|AC|UC|ADR)-(\d{3})(?!\d)")


def defined_ids(root: Path = ROOT) -> dict[str, set[str]]:
    d: dict[str, set[str]] = {k: set() for k in ("FR", "NFR", "FEAT", "AC", "UC", "ADR")}
    for pref, homes in DEF_HOMES.items():
        for h in homes:
            p = root / h
            if p.exists():
                for m in ID_RE.finditer(p.read_text(encoding="utf-8")):
                    if m.group(1) == pref:
                        d[pref].add(m.group(0))
    # ADR defined by file presence + the ArchitectureOverview §7 index
    for adr in (root / "docs/adr").glob("ADR-*.md"):
        mm = re.match(r"(ADR-\d{3})", adr.name)
        if mm:
            d["ADR"].add(mm.group(1))
    ov = root / "docs/architecture/ArchitectureOverview.md"
    if ov.exists():
        for m in ID_RE.finditer(ov.read_text(encoding="utf-8")):
            if m.group(1) == "ADR":
                d["ADR"].add(m.group(0))
    return d


def check_ids(files: list[Path], root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    defined = defined_ids(root)
    for f in files:
        if rel(f, root) in L2_EXEMPT:
            continue
        text = strip_code_spans(f.read_text(encoding="utf-8"))
        for m in ID_RE.finditer(text):
            tok, pref = m.group(0), m.group(1)
            if tok not in defined[pref]:
                errs.append(f"L2 dangling {pref} ref: {rel(f, root)} -> {tok}")
    return errs


# ---------------------------------------------------------------------------
# L2 (/work extension) — TASK/SPRINT/DEBT/RISK id resolution (DEBT-007 item 1)
# ---------------------------------------------------------------------------
# ID formats are law (EngineeringOperatingSystem.md): TASK-NNNN, SPRINT-NN, DEBT-NNN, RISK-NNN.
WORK_ID_RE = re.compile(
    r"(?<![A-Za-z0-9])(?:TASK-(?P<task>\d{4})|SPRINT-(?P<sprint>\d{2})"
    r"|DEBT-(?P<debt>\d{3})|RISK-(?P<risk>\d{3}))(?!\d)"
)

# "Defined includes planned" margin: an undefined id numbered at most (highest currently
# defined id of that prefix + WORK_ID_MARGIN) is treated as a plausible near-future planning
# reference (e.g. a debt-register "Target" cell naming a not-yet-created task), never a
# dangling reference merely for not existing yet.
WORK_ID_MARGIN = 50

# Past-tense completion markers: if one of these sits close to (same clause, same sentence,
# within WORK_ID_CONTEXT_CHARS characters of) an UNDEFINED id, the id is being cited as
# something already done — that is a genuine dangling reference, not a forward plan, and
# must fail regardless of how numerically plausible it is. `closed` excludes `fail-closed`
# (an architecture/security term, not a completion marker — TASK-0010-review.md uses it for
# the fail-closed tenant resolver, immediately adjacent to an unrelated SPRINT-02 forward ref).
WORK_ID_PAST_RE = re.compile(
    r"(?i)\b(resolved|fixed|(?<!fail-)closed|delivered|addressed|shipped|landed|paid|merged"
    r"|completed|implemented)\b"
)
WORK_ID_CONTEXT_CHARS = 45


def _work_id_context(text: str, start: int, end: int) -> str:
    """Same-sentence AND nearby-character window around a match: the narrower of a sentence
    boundary (period/!/?/newline) and a fixed character cap, so a past-tense marker describing
    an unrelated clause many words away in the same long table-row/bullet does not poison an
    adjacent, unrelated id's classification (calibrated against real /work prose — see the
    docs-lint test suite for the specific debt-register/review sentences this guards)."""
    left_bound = 0
    for sep in (". ", "! ", "? ", "\n"):
        idx = text.rfind(sep, 0, start)
        if idx > left_bound:
            left_bound = idx + len(sep)
    right_bound = len(text)
    positions = []
    for sep in (". ", "! ", "? ", "\n"):
        idx = text.find(sep, end)
        if idx != -1:
            positions.append(idx)
    if positions:
        right_bound = min(positions)
    left_bound = max(left_bound, start - WORK_ID_CONTEXT_CHARS)
    right_bound = min(right_bound, end + WORK_ID_CONTEXT_CHARS)
    return text[left_bound:right_bound]


def defined_work_ids(root: Path = ROOT) -> dict[str, set[str]]:
    d: dict[str, set[str]] = {k: set() for k in ("TASK", "SPRINT", "DEBT", "RISK")}
    for p in (root / "work/tasks").glob("TASK-*.md"):
        m = re.match(r"(TASK-\d{4})", p.name)
        if m:
            d["TASK"].add(m.group(1))
    for p in (root / "work/sprints").glob("SPRINT-*.md"):
        m = re.match(r"(SPRINT-\d{2})", p.name)
        if m:
            d["SPRINT"].add(m.group(1))
    debt_file = root / "work/debt-register.md"
    if debt_file.exists():
        for m in re.finditer(r"(?m)^\|\s*(DEBT-\d{3})\s*\|", debt_file.read_text(encoding="utf-8")):
            d["DEBT"].add(m.group(1))
    # RISK-NNN rows actually live in engineering-operating-system/RiskManagementPolicy.md §7 in
    # this repo (RepositoryStructure.md's `/work/risk-register.md` has never been created here —
    # a pre-existing repo fact, not something this task's write-set may touch); union both homes
    # so the check tracks wherever the register actually is, today or in the future.
    for home in ("engineering-operating-system/RiskManagementPolicy.md", "work/risk-register.md"):
        p = root / home
        if p.exists():
            for m in re.finditer(r"(?m)^\|\s*(RISK-\d{3})\s*\|", p.read_text(encoding="utf-8")):
                d["RISK"].add(m.group(1))
    return d


def check_work_ids(files: list[Path], root: Path = ROOT) -> list[str]:
    """Checks TASK/SPRINT/DEBT/RISK id resolution. `files` MUST already be filtered to `/work`
    (the DocumentationStandards §4 scope for this id family) — see main()."""
    errs: list[str] = []
    defined = defined_work_ids(root)
    ceilings = {
        pref: max((int(tok.split("-")[1]) for tok in ids), default=0) + WORK_ID_MARGIN
        for pref, ids in defined.items()
    }
    for f in files:
        text = strip_code_spans(f.read_text(encoding="utf-8"))
        for m in WORK_ID_RE.finditer(text):
            pref = next(k for k, v in m.groupdict().items() if v is not None)
            pref = {"task": "TASK", "sprint": "SPRINT", "debt": "DEBT", "risk": "RISK"}[pref]
            num = m.group(pref.lower())
            tok = f"{pref}-{num}"
            if tok in defined[pref]:
                continue
            s, e = m.span()
            strikethrough = text[max(0, s - 2):s] == "~~" and text[e:e + 2] == "~~"
            past = False
            if not strikethrough:
                window = _work_id_context(text, s, e)
                past = bool(WORK_ID_PAST_RE.search(window))
            plausible = int(num) <= ceilings[pref]
            if past:
                errs.append(
                    f"L2 dangling {pref} ref: {rel(f, root)} -> {tok} "
                    f"(cited in a past/completed context but does not resolve)"
                )
            elif not plausible:
                errs.append(
                    f"L2 dangling {pref} ref: {rel(f, root)} -> {tok} "
                    f"(exceeds plausible near-future ceiling {pref}-{ceilings[pref]:0{len(num)}d})"
                )
    return errs


def check_counts(root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    fc = root / "docs/product/FeatureCatalog.md"
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


# ---------------------------------------------------------------------------
# L3 (Roadmap extension) — DEBT-007 item 3
# ---------------------------------------------------------------------------
# Roadmap.md §1 states a per-phase "Features" count in its summary table; each phase section
# (§4–§9) then enumerates the actual FEAT-ids in scope under its own "N.2 Feature scope"
# heading. Reconcile per-phase, and the sum across phases, the same way check_counts()
# reconciles FeatureCatalog's Total/P0+P1+P2/phase-count row against its actual FEAT rows.
_ROADMAP_SUMMARY_ROW_RE = re.compile(r"(?m)^\|\s*([0-5])\s*\|\s*v[\d.]+\s*\|[^\n|]*\|[^\n|]*\|\s*(\d+)\s*\|\s*$")
_ROADMAP_SECTION_RE = re.compile(r"(?m)^##\s+\d+\.\s+Phase\s+([0-5])\b")
_ROADMAP_SCOPE_RE = re.compile(r"(?m)^###\s+[\d.]+\s+Feature scope\s*\n+(.+?)(?:\n\s*\n|\Z)", re.S)
_FEAT_ID_RE = re.compile(r"FEAT-\d{3}")


def check_roadmap_counts(root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    rp = root / "docs/product/Roadmap.md"
    if not rp.exists():
        return ["L3 Roadmap.md missing"]
    text = rp.read_text(encoding="utf-8")

    stated: dict[str, int] = {}
    for m in _ROADMAP_SUMMARY_ROW_RE.finditer(text):
        stated[m.group(1)] = int(m.group(2))
    if not stated:
        return ["L3 Roadmap §1 Phase summary table not found"]

    section_starts = [(m.start(), m.group(1)) for m in _ROADMAP_SECTION_RE.finditer(text)]
    section_starts.sort(key=lambda t: t[0])
    actual: dict[str, int] = {}
    for i, (start, phase) in enumerate(section_starts):
        end = section_starts[i + 1][0] if i + 1 < len(section_starts) else len(text)
        chunk = text[start:end]
        scope_m = _ROADMAP_SCOPE_RE.search(chunk)
        if not scope_m:
            errs.append(f"L3 Roadmap Phase {phase} Feature scope list not found")
            continue
        actual[phase] = len(set(_FEAT_ID_RE.findall(scope_m.group(1))))

    for phase, count in stated.items():
        if phase in actual and actual[phase] != count:
            errs.append(
                f"L3 Roadmap Phase {phase} stated Features {count} != actual FEAT-id count {actual[phase]}"
            )
    stated_total = sum(stated.values())
    actual_total = sum(actual.get(p, 0) for p in stated)
    if stated_total != actual_total:
        errs.append(
            f"L3 Roadmap Total (sum of §1 Features column) {stated_total} != "
            f"actual total FEAT-id count across phase scopes {actual_total}"
        )
    return errs


# ---------------------------------------------------------------------------
# L4 — canonical-value greps (context-aware; R-DE extends per DocumentationStandards §4).
# Each sub-check below is reported individually (L4-rls-guc / L4-dlq / L4-nfr / L4-locale /
# L4-pnpm), matching the existing per-check PASS/FAIL summary line style.
# ---------------------------------------------------------------------------

# --- L4-rls-guc (original check) -------------------------------------------------------
# The RLS GUC is `app.tenant_id`; `eip.tenant_id` is forbidden ONLY as a GUC expression
# (`eip.*` is legitimately an OTel span-attribute namespace, so the bare token is allowed).
CANON_FORBIDDEN = [
    (r"current_setting\(\s*['\"]eip\.tenant_id", "RLS GUC drift: use app.tenant_id (current_setting)"),
    (r"set_config\(\s*['\"]eip\.tenant_id", "RLS GUC drift: use app.tenant_id (set_config)"),
    (r"SET\s+(?:LOCAL|SESSION)\s+eip\.tenant_id", "RLS GUC drift: use app.tenant_id (SET LOCAL)"),
]


def check_canonical(root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    pats = [(re.compile(rx), msg) for rx, msg in CANON_FORBIDDEN]
    for tree in L4_TREES:
        for f in sorted((root / tree).rglob("*.md")):
            if rel(f, root) in L4_EXEMPT:
                continue
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                for rx, msg in pats:
                    if rx.search(line):
                        errs.append(f"L4-rls-guc drift: {rel(f, root)}:{i} — {msg}")
    return errs


# --- L4-dlq ------------------------------------------------------------------------------
# DLQ topic naming is `<group>.dlq` (the consumer-group name plus the `.dlq` suffix) — never
# the two-tier `<topic>.<group>.dlq` anti-pattern DocumentationQualityReview found and
# DocumentationStandards §4 now names explicitly. This matches on the literal placeholder
# notation, the same literal-pattern style as the L4-rls-guc check above.
DLQ_FORBIDDEN = [
    (re.compile(r"<topic>\.<group>\.dlq"), "DLQ naming drift: use <group>.dlq (never <topic>.<group>.dlq)"),
]


def check_dlq(root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    for tree in L4_TREES:
        for f in sorted((root / tree).rglob("*.md")):
            if rel(f, root) in L4_EXEMPT:
                continue
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                for rx, msg in DLQ_FORBIDDEN:
                    if rx.search(line):
                        errs.append(f"L4-dlq drift: {rel(f, root)}:{i} — {msg}")
    return errs


# --- L4-nfr ------------------------------------------------------------------------------
# Spot-check (best-effort, not exhaustive — see DocumentationStandards §4): a line that
# explicitly cites one of the five CLAUDE.md canonical NFR budgets by ID is expected to state
# that budget's canonical figure. Anchoring on the NFR-ID token (rather than a bare keyword)
# keeps this from flagging the many OTHER "p95"/"p50" SLOs in the doc set (RAG retrieval,
# Kafka consumer lag, audit hash-chain lag, etc.) that legitimately use different numbers and
# do not cite these NFR ids at all.
def _norm_events(v: str) -> int:
    v = v.strip()
    if v.lower().endswith("k"):
        return int(v[:-1].replace(",", "")) * 1000
    return int(v.replace(",", ""))


_NFR_EVENTS_RE = re.compile(r"(\d[\d,]*k?)\s*events\s*/\s*h", re.I)
_NFR_P50_MS_RE = re.compile(r"p50\D{0,10}?(\d+(?:\.\d+)?)\s*ms", re.I)
_NFR_P95_S_RE = re.compile(r"p95\D{0,15}?(\d+(?:\.\d+)?)\s*s\b", re.I)
_NFR_P95_MS_RE = re.compile(r"p95\D{0,10}?(\d+(?:\.\d+)?)\s*ms", re.I)
_NFR_NUM_S_P95_RE = re.compile(r"(\d+(?:\.\d+)?)\s*s\s*p95", re.I)
_NFR_MIN_RE = re.compile(r"(\d+)\s*min", re.I)
_NFR_GB_RE = re.compile(r"(\d+)\s*GB", re.I)

# (NFR id, number-pattern, "is this value acceptable" predicate, human label). NFR-003 accepts
# both the sustained 100,000 events/h figure and its own documented 3x burst (300,000).
NFR_BUDGET_CHECKS = [
    ("NFR-003", _NFR_EVENTS_RE, lambda v: _norm_events(v) in (100_000, 300_000), "100,000 events/h (+ 3x burst)"),
    ("NFR-010", _NFR_P50_MS_RE, lambda v: float(v) == 500, "dashboard p50 < 500 ms"),
    ("NFR-010", _NFR_P95_S_RE, lambda v: float(v) == 2, "dashboard p95 < 2 s"),
    ("NFR-011", _NFR_P95_MS_RE, lambda v: float(v) == 300, "API p95 < 300 ms"),
    ("NFR-012", _NFR_NUM_S_P95_RE, lambda v: float(v) == 60, "webhook freshness 60 s p95"),
    ("NFR-050", _NFR_MIN_RE, lambda v: int(v) == 15, "Compose <= 15 min"),
    ("NFR-050", _NFR_GB_RE, lambda v: int(v) == 16, "16 GB host"),
]
_NFR_ID_TOKEN_RE = re.compile(r"NFR-\d{3}")


def check_nfr(root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    for tree in L4_TREES:
        for f in sorted((root / tree).rglob("*.md")):
            if rel(f, root) in L4_EXEMPT:
                continue
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                ids_present = set(_NFR_ID_TOKEN_RE.findall(line))
                if not ids_present:
                    continue
                for nfr_id, num_re, ok, label in NFR_BUDGET_CHECKS:
                    if nfr_id not in ids_present:
                        continue
                    m = num_re.search(line)
                    if m and not ok(m.group(1)):
                        errs.append(
                            f"L4-nfr drift: {rel(f, root)}:{i} — {nfr_id} states a figure "
                            f"inconsistent with the canonical {label} (found {m.group(0)!r})"
                        )
    return errs


# --- L4-locale ---------------------------------------------------------------------------
# CLAUDE.md canonical: frontend/doc source locale is `en-US`. Forbid `en-GB` outright, and a
# bare `en` locale token (not `en-US`) when the line is actually discussing locale — scoping
# to lines mentioning "locale" avoids flagging the word "en" as it occurs in unrelated prose.
_LOCALE_BAD_EN_GB_RE = re.compile(r"en-GB")
_LOCALE_BARE_EN_RE = re.compile(r"(?<![\w-])en(?!-US)\b")
_LOCALE_CONTEXT_RE = re.compile(r"(?i)locale")


def check_locale(root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    for tree in L4_TREES:
        for f in sorted((root / tree).rglob("*.md")):
            if rel(f, root) in L4_EXEMPT:
                continue
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                if _LOCALE_BAD_EN_GB_RE.search(line):
                    errs.append(
                        f"L4-locale drift: {rel(f, root)}:{i} — found en-GB, canonical locale is en-US"
                    )
                elif _LOCALE_CONTEXT_RE.search(line) and _LOCALE_BARE_EN_RE.search(line):
                    errs.append(
                        f"L4-locale drift: {rel(f, root)}:{i} — bare 'en' locale token, canonical is en-US"
                    )
    return errs


# --- L4-pnpm -----------------------------------------------------------------------------
# CLAUDE.md canonical: frontend package manager is `pnpm`. Forbid lowercase `npm`/`yarn`
# (word-boundary — the artifact-type enum literal `NPM` in DomainModel.md is uppercase and so
# never matches), except inside an explicit "why not X" comparison sentence — allowed when the
# same line carries a negation/contrast word (DependencyManagement.md: "npm/yarn are never
# used").
_PNPM_BAD_RE = re.compile(r"\b(npm|yarn)\b")
_PNPM_NEGATION_RE = re.compile(
    r"(?i)\b(not|instead of|unlike|never|rather than|no longer|isn't|aren't|without)\b"
)


def check_pnpm(root: Path = ROOT) -> list[str]:
    errs: list[str] = []
    for tree in L4_TREES:
        for f in sorted((root / tree).rglob("*.md")):
            if rel(f, root) in L4_EXEMPT:
                continue
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                if _PNPM_BAD_RE.search(line) and not _PNPM_NEGATION_RE.search(line):
                    errs.append(
                        f"L4-pnpm drift: {rel(f, root)}:{i} — frontend package manager is pnpm, "
                        f"found npm/yarn outside a negation/comparison sentence"
                    )
    return errs


def main() -> int:
    files = md_files()
    work_files = [f for f in files if rel(f).startswith("work/")]
    checks = [
        ("L1 links", check_links(files)),
        ("L2 id refs", check_ids(files)),
        ("L2 work ids", check_work_ids(work_files)),
        ("L3 counts", check_counts()),
        ("L3 roadmap", check_roadmap_counts()),
        ("L4-rls-guc", check_canonical()),
        ("L4-dlq", check_dlq()),
        ("L4-nfr", check_nfr()),
        ("L4-locale", check_locale()),
        ("L4-pnpm", check_pnpm()),
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
