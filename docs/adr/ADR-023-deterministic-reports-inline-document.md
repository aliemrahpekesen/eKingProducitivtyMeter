# ADR-023: v0.1 deterministic reports persist an inline `document jsonb`, not MinIO artifacts

- **Status:** Accepted
- **Approver:** Founder direction, ratified R-CA (2026-07-15, TASK-0022)
- **Affected anchors/modules:** [DatabasePlan §3](../engineering/DatabasePlan.md) (`reports.generated_report` planned DDL), `eip-reports`, `eip-app` (`/api/v1/reports`); CC-1 (schema/migration).

## Context

[DatabasePlan §3](../engineering/DatabasePlan.md) plans `reports.generated_report` as artifact
*metadata* — `artifact_keys text[]` pointing at rendered binaries in MinIO/S3, `llm_call_ids`/
`citations` for AI-composed narrative provenance, `validation_status` for a future Validation
Agent verdict — alongside `reports.report_job`/`report_template`/`report_schedule` for a job-runner
state machine. That shape targets the AI-composed report phase (`eip-ai`'s Report Composition
agent), which does not exist yet.

M4 Wave R1 needs a working, useful report *now*: a deterministic exec-summary composing the M3
metric surface (trends, recommendations, in-flight work, friction summary) that a tenant can
generate, list, view, and print/export today — with zero AI involvement, zero new infrastructure
(no MinIO, no job queue), and zero risk of the founder-facing NFR-071 guardrail (team-level only)
being violated by a future LLM-composed narrative.

## Decision

Ship `reports.generated_report` with the DatabasePlan §3 DDL **verbatim**, plus one addition: a
nullable `document jsonb` column carrying the full `ReportDocument` inline — title, period, totals,
per-team sections (trend points, recommendations, in-flight counts), all reused verbatim from the
`eip-analytics` query ports, never recomputed. `generated_by_agent = 'deterministic/exec-summary-v1'`
identifies the (non-AI) generator. `type` is constrained to the full planned enum
(`SPRINT_REVIEW`/`RELEASE_NOTES`/`EXEC_SUMMARY`/`DELIVERY_RISK`/`INCIDENT_ANALYSIS`/`CUSTOM`) so the
column definition does not need to change when later generators land; v0.1 implements only
`EXEC_SUMMARY`. `status` is always `READY` (the deterministic engine completes synchronously — no
`QUEUED`/`GENERATING`/`FAILED` reachable yet). `GET /api/v1/reports/{id}/html` renders `document` to
self-contained HTML on demand (`ReportHtmlRenderer`, pure, no Spring) rather than pre-rendering and
storing a binary artifact.

## Consequences

- **Positive:** a real, useful report ships in M4 with no new infrastructure (no MinIO, no job
  runner, no LLM); the schema is forward-compatible — `document` is additive over the planned DDL,
  so the AI-composed phase can populate `artifact_keys`/`llm_call_ids`/`citations` on new rows
  without another migration touching the columns v0.1 already writes; on-demand HTML rendering
  means there is never a stale pre-rendered artifact to invalidate.
- **Negative:** `reports.report_job`/`report_template`/`report_schedule` and the MinIO artifact path
  are not implemented — v0.1 has no report templates, no subscription/delivery scheduling beyond the
  one built-in weekly cron, and every HTML view re-renders from `document` rather than serving a
  cached file. `document` duplicates the same numbers that could be recomputed from `analytics.*`
  read models on demand — an intentional space/simplicity trade-off (inline document = one query to
  render, no cross-schema joins at read time). Registered as [DEBT-021](../../work/debt-register.md).

## Alternatives rejected

- **Build the full planned schema now** (`report_job`/`report_template`/`report_schedule` + MinIO
  artifacts) — rejected: no job-runner, no template engine, and no object-storage wiring exist yet;
  building all three for one deterministic report type is premature infrastructure for a slice that
  needs none of it (BackendPlan §17 phase mapping puts that infrastructure with the AI-composed
  report phase).
- **Store only `artifact_keys` pointing at a rendered HTML file on disk/MinIO** — rejected: v0.1 has
  no object-storage dependency anywhere yet (ADR-018 scopes vector-store tenancy, not a general
  artifact store); an inline `document` plus on-demand rendering is simpler and avoids introducing
  MinIO as a dependency for one report type.
- **A separate `reports.generated_report_v01` table instead of extending the planned one** —
  rejected: DatabasePlan §3 already names `reports.generated_report` as the artifact-metadata table;
  reusing it (additively) keeps one source of truth and lets the AI-composed phase land as an
  expand-only migration on the same table, not a rename/migrate-data exercise.
