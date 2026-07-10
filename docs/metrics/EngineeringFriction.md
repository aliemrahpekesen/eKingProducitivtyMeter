# Engineering Friction — v0.1 (EXPERIMENTAL)

Metric key `engineering_friction` · version `engineering_friction_v0.1` · grain **team** · TASK-0016.
Source of record for the computed metric the `/api/v1/friction/summary` API and the frontend hero card
surface. Team-level only — never ranks, scores, or surfaces individual developers (FR-057, NFR-071).

## Purpose

Where a team's delivery time is lost: the share of work-item cycle time spent **waiting** (blocked or
in review) rather than actively progressing, plus a bounded **rework** penalty. It answers "where is
engineering time lost?" at the team level, so teams can target their biggest flow drag.

## Grain & inputs

- **Grain:** team (aggregate over the team's completed work items).
- **Inputs:** work-item state-transition timelines, blocked time, review-wait time, rework count,
  cycle time — all derived from the correlated canonical flow (work item → PR → review → build → gate).
- **Determinism:** computed from a fixed input with no clock read and no randomness, so recomputation
  over the same canonical data reproduces every component and the score exactly.

## Per-item decomposition

Each work item's cycle time is split from its ordered state timeline
(`FlowTimeline`, `eip-analytics`): a stage's duration is the gap to the next stage's entry, bucketed by
the stage's state.

| Component | Definition |
|---|---|
| `cycleSec` | resolve time − create time |
| `activeSec` | time in `IN_PROGRESS` |
| `blockedSec` | time in `BLOCKED` |
| `reviewWaitSec` | time in `IN_REVIEW` |
| `waitingSec` | `blockedSec + reviewWaitSec` |
| `reworkCount` | number of `IN_REVIEW → IN_PROGRESS` bounces |

Time in `TODO` counts toward cycle time but is neither active nor waiting.

## Team aggregate & composite formula

Team component seconds are the sums over the team's items. Ratios are shares of total cycle time.
`FrictionCalculator` (`eip-analytics`):

```
waitingRatio   = (blocked + reviewWait) / totalCycle
reworkPerItem  = reworkCount / workItems
frictionScore  = round( min(100, 100 * waitingRatio + 30 * reworkPerItem) )
```

- **Weights:** waiting share is scored 1:1 (0–100); rework adds **30 points per rework-per-item**,
  capped at 100. These weights are the v0.1 choice, documented here and versioned with the metric.
- **Dominant cause:** the larger of the two waiting sinks — `BLOCKED`, `REVIEW_WAIT`, or `NONE`.
- **Flow efficiency** (`active / totalCycle`), **blocked ratio**, and **review-wait ratio** are exposed
  as component percentages.

Higher is worse. It is a **relative team signal, not an SLA** and not a cross-team ranking.

## Worked example (simulation dataset)

Computed from the fixed [SimulationDataset](SimulationDataset.md) (3 teams). Values verified by
`FrictionCalculatorTest` and `FrictionPipelineIntegrationTest`.

| Team | items | active | blocked | review-wait | rework | waitingRatio | score | dominant |
|---|---|---|---|---|---|---|---|---|
| Platform | 3 | 14h | 24h | 56h | 1 | 80/99 | **91** | REVIEW_WAIT |
| Payments | 3 | 11h | 4h | 16h | 0 | 20/36 | **56** | REVIEW_WAIT |
| Web | 3 | 5h | 0h | 9h | 0 | 9/18 | **50** | REVIEW_WAIT |

Worst-first: Platform (91) → Payments (56) → Web (50).

## Caveats

- **EXPERIMENTAL v0.1**, computed from simulation data (labelled as such in the UI).
- A short-cycle team can show a high waiting *share* from small absolute waits; read alongside absolute
  cycle time and throughput.
- Team-level only (NFR-071) — no individual attribution anywhere in the inputs, computation, or output.

## Gaming risks

Skipping or rubber-stamping reviews, splitting items to shrink per-item cycle time, or avoiding the
`BLOCKED` state all understate friction without improving real flow. Surfaced on the card so the number
is read honestly (FEAT-031).

## Provenance & lineage

`analytics.rm_team_friction_current` (read model) + `analytics.metric_fact` (time series) are computed
by the friction pipeline from `analytics.flow_correlation` (evidence) ← canonical `work`/`scm`/`cicd`/
`quality` ← `staging.raw_simulation` ← the simulation connector. The API no longer reads the seed-only
`rm_team_flow_current`. Async Kafka transport is deferred (DEBT-017); the SPI is a v0.1 subset
(DEBT-018).
