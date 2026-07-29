# Reports — v0.1 (deterministic engine)

The `EXEC_SUMMARY` report type (TASK-0022 M4 Wave R1, ADR-023): a versioned document composing the
M3 metric surface — weekly trends, rule-based recommendations, in-flight work, and the friction
summary — into one artifact, persisted at `reports.generated_report` and rendered on demand to
self-contained HTML. No AI is involved in v0.1; every number is read from data already computed by
`eip-analytics`, never recomputed. Team-level only — never ranks, scores, or surfaces individual
developers (FR-057, NFR-071).

## Composition

`ReportGenerationService` (`eip-reports`, `application`, implements `GenerateReportUseCase`) calls
four analytics query ports directly for the current tenant — the same ports and the same
direct-call pattern `MetricsController`/`InsightsController`/`FrictionController` use, each port
managing its own tenant-bound transaction:

- `GetMetricTrendsQuery.trends(weeks)` — the requested window's weekly per-team trend points.
- `GetRecommendationsQuery.recommendations()` — every team's currently fired recommendations.
- `GetInFlightWorkQuery.inFlight()` — every team's currently unresolved work.
- `GetFrictionSummaryQuery.summary()` — every team's current computed friction score + dominant
  cause, worst-first.

The pure, framework-free `ReportComposer` (no Spring, no JDBC) assembles the `ReportDocument` from
these four views plus one wall-clock read, `generatedAt` (`Instant.now()`, taken once) — bookkeeping
only, never an input to a computed value except the one documented empty-data fallback below.
`ReportRepository` then persists the document (`document` jsonb) and returns the `ReportView`
summary.

## Determinism guarantee

Recomputing a report from the same underlying data — the same trends/recommendations/in-flight/
friction-summary snapshot — always reproduces the same `totals` and `teams` content, byte-for-byte,
except `generatedAt` (and the row's own `id`/`created_at`). There is no randomness and no hidden
clock read inside `ReportComposer`'s arithmetic or assembly.

**Empty-data exception.** If no team has resolved anything in the requested window (a fresh tenant
with no data yet), there is no period in the data to derive `periodStart`/`periodEnd` from; the
composer falls back to a `generatedAt`-anchored window (`periodEnd` = `generatedAt`'s UTC date,
`periodStart` = `periodEnd` minus `weeks` weeks). This is the one case where the wall-clock read
feeds a computed value — every other field derives strictly from the four composed reads.

## Period derivation

`periodStart` is the inclusive start: the earliest week (`weekStart`, a Monday) across every team's
trend points, at UTC midnight. `periodEnd` is the exclusive end: the latest such week plus 7 days, at
UTC midnight — so `[periodStart, periodEnd)` covers exactly the resolved weeks in the window. The
title is `"Engineering Flow Report — {periodStart date}..{periodEnd date}"` (en-US), e.g.
`"Engineering Flow Report — 2026-01-05..2026-01-19"`.

## Totals formulas

`ReportTotals` aggregates across every team's weekly trend points in the window (not the friction
summary's single current values, except `avgFrictionScore`):

- **`itemsResolved`** — simple sum of `itemsResolved` across every point of every team.
- **`teamsReporting`** — the friction summary's own `teamsReporting` (teams with computed friction).
- **`avgCycleSec`, `p85CycleSec`, `flowEfficiencyPct`, `blockedPct`, `reviewWaitPct`** —
  items-resolved-weighted means across every point: for a field `X`,

  ```
  weighted(X) = round( Σ (X_point × itemsResolved_point) / Σ itemsResolved_point )
  ```

  (`0` when the total `itemsResolved` is `0`). A week with more resolved items counts more toward
  the total — consistent with how `avgCycleSec`/`p85CycleSec`/the percent fields are themselves
  computed per week in `TrendService`.
- **`avgFrictionScore`** — the **unweighted** mean of every reporting team's *current* friction
  score (`FrictionSummaryView.teams()[].frictionScore`), not derived from the weekly points: a
  team's friction score is a single current value, not a per-week series, so weighting it by
  `itemsResolved` would conflate two different kinds of numbers.

**Worked example** (`ReportComposerTest`, asserted to the digit): two teams, two weeks each —
Alpha (10 items/week1, 20/week2) and Beta (5/week1, 15/week2) — `itemsResolved = 50`,
`avgCycleSec = round(7300/50) = 146`, `p85CycleSec = round(11700/50) = 234`,
`flowEfficiencyPct = round(2425/50) = round(48.5) = 49`, `blockedPct = round(975/50) = 20`,
`reviewWaitPct = round(1350/50) = 27`; with friction scores 80 (Alpha) and 40 (Beta),
`avgFrictionScore = round((80+40)/2) = 60`.

## Team sections

`ReportTeamSection` is built per team, ordered worst-first (mirroring the friction summary's own
order): `frictionScore`/`dominantCause` come from the friction summary (current, not historical);
`points` are the team's own weekly trend points reused verbatim (`TrendPointView`, never cloned);
`recommendations` are the team's own fired recommendations reused verbatim (`RecommendationView`);
`inFlightCount`/`blockedInFlightCount` come from the in-flight view (`items().size()` and the count
with `blocked = true`). A team present in the friction summary but absent from trends/recommendations
/in-flight gets an empty list / zero counts for the missing signal — never fabricated.

## Rendering rules

`ReportHtmlRenderer` (pure, no Spring) renders a `ReportDocument` to one self-contained HTML string:

- Inline CSS only (`max-width: 800px`, sans-serif, `@media print` rules, `page-break-inside: avoid`
  on each team `<section>`); no `<script>`, no external assets — the rendered page is the whole
  response body.
- Every dynamic string (title, team name, dominant cause, metric version, recommendation text,
  in-flight/trend values that originate as strings) is HTML-escaped (`&`, `<`, `>`, `"`, `'`) before
  being written — proven against a team name containing `<script>` in `ReportHtmlRendererTest`.
  There is no template-injection surface: the renderer only ever concatenates escaped strings and
  formatted numbers into a fixed HTML skeleton.
- Numbers format with `Locale.US` explicitly (`DecimalFormat`/`NumberFormat`), independent of the
  JVM default locale. Durations (`avgCycleSec`, `p85CycleSec`) render as hours with one decimal
  place (`seconds / 3600.0`, e.g. `5400s → "1.5"`).
- Sections, in order: header (title, period, `generatedAt`, metric version), totals table, one
  section per team (friction score + dominant cause, a weekly table when the team has points,
  a recommendations list when non-empty, in-flight counts), and a footer stating
  `"Deterministic report v{reportVersion} — computed from recorded flow data; no AI involved."`

## API surface

`POST /api/v1/reports {type, weeks}` → `201` + `Location`; `GET /api/v1/reports?cursor&limit` →
cursor-paginated list (`ReportPageView`, keyset over `(created_at desc, id desc)`, default limit 20,
max 100); `GET /api/v1/reports/{id}` → `ReportDocumentView`; `GET /api/v1/reports/{id}/html` → the
rendered page, `Content-Type: text/html;charset=UTF-8`, `Content-Disposition: inline`. `type` must be
`"EXEC_SUMMARY"` (v0.1's only generator) and `weeks` must be in `4..52`; both violations are `400`
problem+json.

## Scheduled generation

`ScheduledReportRunner` (`eip-app`) generates a 4-week `EXEC_SUMMARY` report for every tenant weekly
(default Monday 06:00 server time, `eip.reports.schedule-cron`), gated by
`eip.reports.scheduled-enabled` (default on). One tenant's failure is caught and counted
(`eip_reports_scheduled_ok` / `eip_reports_scheduled_failed`), never stopping the loop.

## Versioning

This is **reports v0.1** (`ReportDocument.reportVersion = 1`, `generated_by_agent =
"deterministic/exec-summary-v1"`). Changing the totals formulas, the period derivation, the
rendering rules, or the document shape above is a new version and must be reflected here before the
code changes (Law 1: docs-first). See [ADR-023](../adr/ADR-023-deterministic-reports-inline-document.md)
for the schema/scope decision and [DEBT-021](../../work/debt-register.md) for what v0.1 defers.
