# Recommendations — v0.1

Rule-based recommendation engine over the Engineering Friction v0.1 read model, exposed at
`GET /api/v1/insights/recommendations`. Team-level only — never ranks, scores, or surfaces individual
developers (FR-057, NFR-071). Computed by `com.eip.analytics.friction.RecommendationEngine`
(`eip-analytics`), a pure function of `TeamSignals` — no clock read, no randomness, no I/O.

## Determinism

`RecommendationEngine.evaluate(TeamSignals)` is a pure static function: the same inputs always
produce the same recommendations, in the same order. The one non-deterministic *input* is
`maxInFlightAgeSec` (derived from `now() - createdInSource` on the in-flight work view — the single
sanctioned wall-clock read in the M3 metrics surface, documented on `InFlightItemView`); the engine
itself never reads a clock. A healthy team — no rule fires — yields an empty recommendation list
rather than a fabricated "all clear" entry.

## Composition

`RecommendationService` (`eip-analytics`, `application`) builds one `TeamSignals` per team inside a
single tenant-bound read-only transaction, combining:

- The computed friction read model (`FrictionReadRepository.summaryRows()` — reused, not
  recomputed): `frictionScore`, `dominantCause`, `workItems`, `flowEfficiencyPct`, `blockedPct`,
  `reviewWaitPct`, `reworkCount`, and `avgCycleSec = totalCycleSec / workItems` (`0` when
  `workItems` is `0`).
- The in-flight work aggregate (`InFlightRepository.aggregates()`): `inFlightCount` and
  `maxInFlightAgeSec` for the team (both `0` when the team has no unresolved items).

Reading both signals in the same transaction keeps them a consistent snapshot.

## Rules (v1)

Five independent rules; any subset — including none — may fire per team. Ordering is deterministic:
severity `CRITICAL` > `WARN` > `INFO`, then `code` ascending.

| Code | Condition | Severity | Title | Actions |
|---|---|---|---|---|
| `R-REVIEW-WAIT` | `reviewWaitPct >= 40` | `CRITICAL` if `>= 55`, else `WARN` | Review wait dominates cycle time | Add reviewer capacity · Set review SLAs · Reduce PR size |
| `R-BLOCKED` | `blockedPct >= 25` | `CRITICAL` if `>= 40`, else `WARN` | Blocked time is eroding delivery | Run daily blocker triage · Escalate external dependencies · Track blocker owners at the team level |
| `R-REWORK` | `workItems > 0 && reworkCount*10 >= workItems*3` (rework ratio ≥ 0.3) | `WARN` | High rework loop rate | Strengthen definition-of-ready · Add earlier design review · Pair on ambiguous items |
| `R-FLOW-EFFICIENCY` | `flowEfficiencyPct < 30` | `WARN` (rationale notes "severely low" when `< 20`; no separate `CRITICAL` tier) | Low flow efficiency | Lower WIP limits · Swarm on oldest items |
| `R-AGING-WIP` | `avgCycleSec > 0 && inFlightCount > 0 && maxInFlightAgeSec > 2 * avgCycleSec` | `WARN` | Aging work in progress | Review oldest in-flight items · Split or close stale work |

Every fired recommendation's `rationale` embeds the team's actual numbers (e.g. "Review wait
consumes 57% of cycle time across 3 work item(s)…"), and `metricRefs` names the inputs that drove it
(e.g. `["reviewWaitPct=57"]`) — a recommendation is never an unexplained assertion.

Integer arithmetic is used for the rework ratio comparison (`reworkCount*10 >= workItems*3`) to avoid
floating-point rounding at the 0.3 boundary.

## NFR-071 (team-level only)

`TeamSignals` and `Recommendation` carry no individual identifier — no assignee, reporter, reviewer,
author, or other person field, anywhere in the input or the output. Recommendations name artifacts
(counts, percentages, ages) and team actions, never a person.

## Versioning

This is **recommendations v0.1** — the rule set, thresholds, and copy above. Changing a threshold,
adding a rule, or changing the ordering is a new version and must be reflected here before the code
changes (Law 1: docs-first).
