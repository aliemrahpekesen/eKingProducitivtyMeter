# EIP Acceptance Criteria (P0/P1 Features)

Acceptance criteria for all P0 and P1 features of the **Engineering Intelligence Platform (EIP)**. Each criterion has a stable ID (`AC-XXX`) and references the feature(s) it verifies (`FEAT-XXX`, see `./FeatureCatalog.md`). Criteria are written in Given/When/Then form and are testable as automated integration/E2E tests unless noted. Phase mapping is in `./Roadmap.md`.

## 1. Definition of Done (applies to every feature)

A feature is Done only when all of the following hold, in addition to its specific acceptance criteria:

- [ ] Code merged behind reviewed PR; module boundaries respected (Spring Modulith verification passes).
- [ ] Unit + integration tests written and green in CI; new logic covered.
- [ ] All applicable AC-XXX below automated or explicitly test-planned.
- [ ] Tenant isolation verified for every new table (RLS policy present), API endpoint, cache key, Kafka consumer, and object-storage path introduced.
- [ ] RBAC permission(s) defined for every new endpoint/UI action; denied-path test exists.
- [ ] OpenAPI 3 spec updated; errors use RFC 7807 problem+json; cursor pagination on list endpoints.
- [ ] OTel spans, Micrometer metrics, and structured JSON logs emitted for new operations; no secrets or raw prompts in logs.
- [ ] Audit events emitted for security-relevant or configuration-changing actions.
- [ ] Flyway migration is forward-only, idempotent on re-run, and tested against a seeded database.
- [ ] Works in the Docker Compose stack and in simulation mode where the feature touches connectors, agents, or reports.
- [ ] Works air-gapped (no SaaS calls) unless the feature is an explicitly configured external LLM provider.
- [ ] Documentation in `/docs` updated; metric features include purpose, formula, inputs, grain, caveats/limitations, and gaming risks.
- [ ] No individual-surveillance or stack-ranking surface introduced (metric governance review, FEAT-200).

## 2. Platform & Tenancy

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-001 | FEAT-001 | Given two Organizations A and B with data in every tenant-scoped table, When any API request authenticated for tenant A queries any resource by ID belonging to tenant B, Then the response is 404 (not 403, to avoid existence disclosure) and no tenant-B row is returned, for 100% of endpoints in an automated cross-tenant sweep test. |
| AC-002 | FEAT-001 | Given a direct SQL session with tenant context set to tenant A, When any query touches a tenant-scoped table, Then Postgres RLS filters out all tenant-B rows even if application-level filtering is removed. |
| AC-003 | FEAT-003 | Given Keycloak is configured as OIDC provider, When a user completes the OIDC login flow, Then a tenant-scoped session is established with roles mapped from IdP claims; and Given the IdP is unreachable, When a break-glass local admin logs in via local-accounts fallback, Then login succeeds and the event is audited. |
| AC-004 | FEAT-004 | Given a user with the viewer role, When they invoke any mutating endpoint (POST/PUT/PATCH/DELETE), Then the response is 403 problem+json with a stable error type, and an authorization-denied audit event is recorded. |
| AC-005 | FEAT-004 | Given a custom role granting exactly the `metrics:read` permission, When the user calls metric query endpoints, Then requests succeed; and When the same user calls connector admin endpoints, Then requests are denied — verifying fine-grained permission enforcement, not just role names. |
| AC-006 | FEAT-005 | Given any failed API request, When the response is returned, Then it is RFC 7807 problem+json with `type`, `title`, `status`, and a correlation ID matching the request trace. |
| AC-007 | FEAT-005 | Given a mutating batch endpoint called twice with the same idempotency key, When the second call arrives, Then no duplicate side effects occur and the original result is returned. |
| AC-008 | FEAT-005 | Given a list endpoint with more results than one page, When the client follows cursor pagination to exhaustion, Then every item is returned exactly once and cursors remain stable under concurrent inserts. |
| AC-009 | FEAT-007 | Given a clean host with Docker, When `docker compose up` is run in `/infra/docker-compose`, Then the full stack (app, workers, PostgreSQL+pgvector, Redis, Kafka, MinIO, Keycloak, OTel Collector, Prometheus, Grafana) is healthy within 10 minutes and the demo tenant is reachable. |
| AC-010 | FEAT-010 | Given a running instance with data, When the documented backup is taken and restored to a fresh environment, Then all tenant data, artifacts, checkpoints, and audit logs are intact and a checksum-based verification report passes. |

## 3. Admin & Configuration

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-011 | FEAT-020 | Given a platform admin, When they create an Organization with a first tenant admin, Then the tenant is bootstrapped (default roles, empty data source list) and the tenant admin can log in with no access to any other tenant. |
| AC-012 | FEAT-022 | Given Teams with Member assignments and Repository/Project mappings, When metrics are computed, Then attribution follows the configured mappings and unmapped entities appear in an "unattributed" bucket rather than being silently dropped. |
| AC-013 | FEAT-023 | Given a tenant admin deactivates a user, When that user's existing token is next used, Then access is denied within 60 seconds and the deactivation is audited with actor and target. |
| AC-014 | FEAT-024 | Given a connector configuration form, When the admin submits values violating the connector's JSON Schema, Then field-level validation errors are shown and nothing is persisted; When values pass `validate()`, Then `testConnection()` can be run before saving. |
| AC-015 | FEAT-024, FEAT-050 | Given a configured Jira data source with valid credentials, When the admin clicks "Test connection", Then the result (success, or categorized failure: auth/network/permission/rate-limit) is returned within 30 seconds without starting a sync and is recorded in the data source's history. |
| AC-016 | FEAT-025, FEAT-195 | Given a stored connector credential, When the database and backups are inspected, Then the secret exists only as AES-256-GCM ciphertext with a wrapped data key; no plaintext secret appears in DB, logs, traces, or Kafka messages. |
| AC-017 | FEAT-025 | Given any UI or API view of a credential, When it is displayed, Then only a masked form (e.g., `****last4`) is shown; the full value is never returned by any read endpoint after creation. |
| AC-018 | FEAT-025, FEAT-197 | Given any decryption/use of a stored secret by a connector, job, or admin action, When it occurs, Then an audit event records the accessor identity (user or system principal), secret reference, purpose, and timestamp. |
| AC-019 | FEAT-025, FEAT-196 | Given a credential rotation, When the new secret is saved, Then subsequent syncs use it immediately, `testConnection()` re-verifies health, and the old ciphertext is invalidated; rotation is audited. |
| AC-020 | FEAT-026 | Given per-agent model routing configured (e.g., Sprint Review Agent → local vLLM model), When that agent runs, Then the configured provider/model is used and the LLM call audit shows it; tenant-level defaults apply when no agent override exists. |
| AC-021 | FEAT-031 | Given a tenant remaps WorkflowStates to metric stages (e.g., "In Review" counts as active, not waiting), When flow metrics are recomputed, Then cycle time and flow efficiency reflect the new mapping and the change is versioned and audited. |
| AC-022 | FEAT-033 | Given a retention policy of 90 days for raw staging data, When the retention job runs, Then only raw records older than 90 days for that tenant are removed, canonical data is unaffected, and the deletion is summarized in an audit event. |
| AC-023 | FEAT-035 | Given a scheduled job that fails, When the failure occurs, Then the run history shows status, error, and duration; the job retries per its policy; and repeated failures raise a notification (FEAT-034) and appear on the job monitor (FEAT-120). |
| AC-024 | FEAT-034 | Given a configured webhook notification channel, When a subscribed event fires (e.g., risk threshold breach), Then the webhook is delivered with retry/backoff and delivery status is visible to the admin. |

## 4. Connectors, Ingestion & Normalization

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-025 | FEAT-050, FEAT-078 | Given a connector's `incrementalSync(checkpoint)` is interrupted mid-run (worker killed), When the sync engine restarts, Then sync resumes from the last committed checkpoint, no already-committed entity is re-fetched beyond the checkpoint window, and no entity in the interrupted window is lost. |
| AC-026 | FEAT-078 | Given a completed full sync followed by source-system changes, When incremental sync runs, Then only changed/new entities are fetched (verified against source API call counts) and the checkpoint advances monotonically per connector+stream. |
| AC-027 | FEAT-080 | Given the same source payloads are ingested twice (full re-ingestion after a replay), When normalization completes, Then canonical entity counts are unchanged, upserts are keyed by `ExternalRef` (sourceSystem, externalId), and no duplicate WorkItem/Commit/PullRequest rows exist. |
| AC-028 | FEAT-080, FEAT-076 | Given a Kafka consumer receives the same event twice (at-least-once delivery), When both deliveries are processed, Then the second is deduplicated on `eventId` and downstream metrics are unaffected. |
| AC-029 | FEAT-076 | Given any domain event published, When it is inspected on its `eip.*` topic, Then the envelope contains all required fields (`eventId` UUIDv7, `tenantId`, `source`, `entityType`, `entityId`, `eventType`, `occurredAt`, `ingestedAt`, `schemaVersion`, `payload`, `traceparent`) and events for the same tenantId+entityId are consumed in order. |
| AC-030 | FEAT-077 | Given a Jira epic, story, and bug are synced, When normalization runs, Then each becomes a `WorkItem` of the correct type (EPIC/STORY/BUG) with an `ExternalRef` carrying sourceSystem `jira`, the source key, and a resolvable URL. |
| AC-031 | FEAT-081 | Given a poison message that fails processing after configured retries, When retries are exhausted, Then the message lands on the consumer group's `.<group>.dlq`, an alert fires on DLQ growth, and an operator can replay it after fix-forward with successful processing. |
| AC-032 | FEAT-082 | Given a source API returns 429 with a Retry-After header, When the connector retries, Then it honors the header, applies exponential backoff + jitter, and the sync completes without dropping data or exceeding the configured rate limit. |
| AC-033 | FEAT-079 | Given a registered GitHub webhook with a signing secret, When an event with an invalid signature arrives, Then it is rejected and audited; When a valid event arrives, Then the corresponding canonical entity is updated within 60 seconds. |
| AC-034 | FEAT-083 | Given a PullRequest whose branch name and commits reference a Jira key, When correlation runs, Then the PullRequest is linked to that WorkItem and the link is visible via API and used by lead-time-for-changes computation. |
| AC-035 | FEAT-051 | Given a tenant configured with simulation-mode connectors and the simulated enterprise data pack, When full sync runs, Then canonical entities, domain events, metrics, dashboards, and reports are produced through exactly the same code paths as live mode, and switching a data source between simulation and live requires only configuration change (simulation mode parity). |
| AC-036 | FEAT-050 | Given a live connector becomes unreachable mid-operation (connector down), When users view dashboards and reports, Then previously ingested data remains fully available and marked with data-freshness timestamps, the data source health shows degraded with a categorized error, other connectors continue syncing unaffected, and no dashboard or report request fails due to the outage (graceful degradation). |
| AC-037 | FEAT-050 | Given a connector `healthCheck()` schedule, When a connector transitions healthy→unhealthy or back, Then the transition is recorded, surfaced on the connector monitor (FEAT-120) and admin dashboard (FEAT-111), and a notification fires per subscription rules. |

## 5. Analytics & Metrics

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-038 | FEAT-090 | Given any metric shown in the UI or API, When its definition is requested, Then purpose, formula, inputs, grain, caveats/limitations, and gaming risks are returned and rendered (e.g., as a dashboard tooltip/info panel). |
| AC-039 | FEAT-091 | Given a WorkItem that entered "In Progress" at 2026-03-02T09:00Z and reached "Done" at 2026-03-06T09:00Z, When cycle time is computed with a 24x7 calendar, Then the value is exactly 96 hours (4.0 days); and with a Mon–Fri working calendar, Then non-working days are excluded per the tenant's calendar configuration. Concrete fixture data for this case ships in the metric test suite. |
| AC-040 | FEAT-091 | Given a WorkItem created 2026-03-01 and done 2026-03-10 that was blocked 2026-03-04 through 2026-03-06, When lead time, cycle time, and blocked time are computed, Then blocked time is 2 days, it is included in cycle time but reported separately, and flow efficiency = active time / cycle time is consistent with those values. |
| AC-041 | FEAT-091 | Given a Sprint committed with 20 story points at start, 5 points added and 3 removed mid-sprint, and 18 points done at close, When sprint metrics compute, Then scope churn = (5+3)/20 = 40% and sprint predictability (commitment vs done) = 18/20 = 90%, matching the shipped formula documentation. |
| AC-042 | FEAT-092 | Given 10 production Deployments in a window, of which 2 caused a remediation (rollback, hotfix deployment, or linked Incident), When change failure rate is computed, Then CFR = 2/10 = 20%; the shipped test fixture encodes exactly this scenario: deployments D1..D10, with D3 followed by a rollback and D7 linked to Incident INC-42, expecting 20%. |
| AC-043 | FEAT-092 | Given correlated Commit→PullRequest→Deployment chains, When lead time for changes is computed, Then it measures first-commit-to-production-deployment per change and reports the median over the window, with uncorrelated deployments counted and disclosed as coverage caveat rather than silently ignored. |
| AC-044 | FEAT-092 | Given an Incident opened 10:00 and resolved 14:30 affecting a Service, When MTTR is computed over the window, Then this incident contributes 4.5 hours and deployment frequency counts only successful production deployments per the definition. |
| AC-045 | FEAT-093 | Given SonarQube reports coverage 71.4%, 120 code smells, and a failed QualityGate for a project, When quality metrics sync, Then dashboard values match the source exactly for the same analysis snapshot, with snapshot timestamp displayed. |
| AC-046 | FEAT-094, FEAT-097 | Given a risk rule "release readiness < 70 raises Risk", When the score crosses the threshold, Then a Risk record is created with the contributing signals listed (explanation), and it clears automatically when the score recovers, with both transitions audited and notifiable. |
| AC-047 | FEAT-096, FEAT-200 | Given team health metrics are enabled, When any API or UI request attempts to retrieve person-grain productivity comparisons (e.g., commits per individual ranked), Then the request is refused by design (no such endpoint/permission exists) and team-level aggregates include the anti-ranking context statement. |
| AC-048 | FEAT-098 | Given metrics computed for a Team, When queried via `/api/v1` with roll-up to BusinessUnit and Organization, Then aggregates equal the documented aggregation of constituent team values and results paginate with cursors. |

## 6. Dashboards

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-049 | FEAT-114 | Given webhooks are active for the sprint's data source, When a WorkItem changes state in the source tool, Then the sprint dashboard reflects it within 5 minutes; and Given only scheduled sync, Then within one sync interval; in both cases the dashboard displays "data as of" freshness per data source. |
| AC-050 | FEAT-114 | Given a running Sprint with the AC-041 fixture, When the sprint dashboard renders, Then burndown, commitment vs done, scope churn, and blocked items match the metric engine's values exactly (single source of truth — no dashboard-side recomputation). |
| AC-051 | FEAT-110 | Given a user filters any dashboard by Team and time range, When they drill down from a chart point, Then they reach the underlying entity list (WorkItems, Deployments, Incidents) matching that data point's filter context. |
| AC-052 | FEAT-112 | Given the productivity dashboard, When rendered for any team, Then every widget shows team-grain data only, includes metric caveat tooltips (AC-038), and no view ranks individuals. |
| AC-053 | FEAT-113 | Given delivery risks exist, When the delivery risk dashboard renders, Then each risk shows score, trend, contributing signals, and the rule version that produced it, with drill-down to affected Epics/Dependencies. |
| AC-054 | FEAT-119, FEAT-120 | Given an operator views system health and monitors, When Kafka consumer lag, DLQ depth, job failures, cache hit rate, or a connector checkpoint age exceeds thresholds, Then the affected component is visibly degraded on the dashboard within one scrape interval and links to runbook documentation. |
| AC-055 | FEAT-111 | Given a tenant admin opens the admin dashboard, When any data source is unhealthy or a scheduled job failed in the last 24h, Then this is visible above the fold with links to the failing item. |

## 7. AI Agents & LLM Infrastructure

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-056 | FEAT-130 | Given any agent run, When it executes, Then it operates under a plan/execute loop with an enforced token/cost budget; exceeding the budget aborts the run with a partial-result explanation and an audited budget-exceeded outcome. |
| AC-057 | FEAT-130, FEAT-198 | Given any LLM invocation by any agent, When it completes (success or failure), Then an LLM call audit record exists with prompt (redacted per policy), model, provider, tokens, cost, latency, agent, tenant, and triggering user/job — verified by counting invocations vs audit rows in a test run (must be equal). |
| AC-058 | FEAT-131 | Given only a local Ollama provider is configured in an air-gapped environment, When agents run, Then all AI features function with zero outbound network calls (verified by egress capture in the test environment). |
| AC-059 | FEAT-132 | Given a provider fallback chain (primary vLLM, fallback Ollama), When the primary provider is down or times out, Then the run transparently retries on the fallback, the output notes the model actually used, and the failover is recorded in the LLM call audit; When both fail, Then the job fails gracefully with a problem+json error and no partial artifact is published. |
| AC-060 | FEAT-137 | Given a completed Sprint in the simulation data pack, When the Sprint Review Agent runs, Then the narrative covers commitment vs done, scope churn, blockers, and carry-over, and every quantitative claim carries a citation resolvable to a canonical entity or metric query. |
| AC-061 | FEAT-138 | Given a Release with merged PullRequests linked to WorkItems, When the Release Notes Agent runs, Then every listed change traces to at least one PullRequest or WorkItem citation, and items without correlation are listed under "uncategorized changes" rather than invented. |
| AC-062 | FEAT-136 | Given an Epic with rising scope churn and a blocked critical Dependency, When the Delivery Risk Agent runs, Then its narrative names the contributing signals with citations and states uncertainty; it never asserts a delay as certain. |
| AC-063 | FEAT-148 | Given a generated report where one claim lacks a resolvable citation, When the Validation Agent reviews it, Then the report is blocked from publication (or annotated per policy) and the validation outcome is stored with the artifact version. |
| AC-064 | FEAT-134 | Given a data gap (e.g., a repository with no synced commits for 14 days while the connector reports healthy), When the Data Quality Agent runs, Then a data-quality finding is raised and affected metrics display a data-quality warning annotation. |
| AC-065 | FEAT-135 | Given a metric moved more than its configured significance threshold week-over-week, When the Engineering Metrics Agent explains it, Then the explanation cites the underlying entity changes (e.g., specific large WorkItems, holiday calendar) and includes the metric's caveats. |
| AC-066 | FEAT-142 | Given a portfolio with mixed delivery health, When the Executive Summary Agent runs, Then the summary is within the configured length budget, covers delivery, risk, quality, and ops posture, and all figures match the metrics API for the same time window. |
| AC-067 | FEAT-147 | Given a multi-section report request, When the Report Composition Agent assembles sections from other agents, Then the final document follows the selected template's structure, has a complete citation index, and fails composition (not silently truncates) if a mandatory section is missing. |

## 8. RAG

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-068 | FEAT-155, FEAT-156 | Given canonical entities and Documents for a tenant, When RAG ingestion runs, Then chunks are stored with metadata (tenant, source, entity type, permissions, timestamps) and embeddings from the configured embedding model; re-running ingestion is idempotent (no duplicate chunks). |
| AC-069 | FEAT-158 | Given users U1 (access to Project P1 only) and U2 (access to P1 and P2), When each issues the same retrieval query, Then U1's results contain zero chunks sourced from P2 entities while U2's may contain both — verified with seeded corpora (permission-aware retrieval). |
| AC-070 | FEAT-158, FEAT-001 | Given tenants A and B with semantically similar documents, When any retrieval executes for tenant A — including retrievals issued internally by agents and MCP-exposed capabilities — Then zero tenant-B chunks are ever returned, verified by an adversarial test seeding near-identical content in both tenants. |
| AC-071 | FEAT-158 | Given any retrieval result used in a generated output, When the output is produced, Then each retrieved chunk carries a source citation (entity/document reference and URL where available) that resolves for a user with permission to the source. |
| AC-072 | FEAT-159 | Given a source Document is updated or deleted, When incremental re-indexing processes the change event, Then retrieval reflects the update within the configured re-index SLA and deleted content is no longer retrievable. |
| AC-073 | FEAT-160 | Given any RAG retrieval, When it executes, Then an audit record captures the caller (user/agent), tenant, filters, corpus scope, and cited documents, queryable by audit reviewers. |
| AC-074 | FEAT-157 | Given a tenant switches vector store backend from pgvector to Qdrant via the VectorStore SPI, When re-indexing completes, Then retrieval behavior (isolation, filters, citations) passes the same test suite with no application-code change. |

## 9. MCP

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-075 | FEAT-165, FEAT-167 | Given a registered external MCP server and an agent whose tool allow-list excludes one of its capabilities, When the agent attempts that capability, Then the call is denied and audited; allowed capabilities execute and are audited with caller, capability, parameters (per redaction policy), and outcome. |
| AC-076 | FEAT-166, FEAT-167 | Given EIP's MCP server exposes only allow-listed capabilities, When an external MCP client lists capabilities, Then only the allow-list is visible; When it invokes one, Then tenant scoping and per-capability RBAC are enforced exactly as for the REST API, including AC-070 tenant isolation. |
| AC-077 | FEAT-166 | Given a capability is removed from the allow-list, When a previously connected MCP client invokes it, Then the invocation is refused immediately (no cached authorization) and the refusal is audited. |

## 10. Report Generation Center & Artifacts Library

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-078 | FEAT-170 | Given a report request submitted via the Report Generation Center, When the job runs asynchronously via `eip.reports.jobs`, Then progress and terminal status (success/failure with diagnostics) are visible in run history, and failures never publish partial artifacts. |
| AC-079 | FEAT-172 | Given a generated report, When it is stored, Then the Artifacts Library records an immutable new version with metadata (type, parameters, generating agent chain, template version, model(s) used, citation index), and prior versions remain retrievable — regeneration always creates version N+1, never overwrites. |
| AC-080 | FEAT-172, FEAT-004 | Given an artifact scoped to Project P1, When a user without P1 access requests it, Then access is denied and audited; the artifact list only shows artifacts the caller may read. |
| AC-081 | FEAT-174 | Given a completed Sprint in the simulation pack, When the sprint review presentation is generated, Then a PPTX/HTML deck is produced containing goals vs outcomes, metric charts consistent with the sprint dashboard, blockers, and a citations appendix; the same request in live mode produces the same structure (simulation parity). |
| AC-082 | FEAT-171 | Given a tenant overrides a shipped report template, When reports generate, Then the tenant override is used and the template version is recorded on the artifact; reverting the override restores default behavior without affecting existing artifact versions. |
| AC-083 | FEAT-175, FEAT-177 | Given release notes and risk report generation with citations required, When any generated claim is spot-checked via its citation link, Then the cited entity exists and supports the claim; artifacts failing Validation Agent policy (AC-063) are not published. |
| AC-084 | FEAT-173 | Given a scheduled weekly status report with an email channel, When the schedule fires, Then the report generates, a new artifact version is stored, and delivery includes a link to the Artifacts Library entry; delivery failures are retried and surfaced. |

## 11. Security & Audit

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-085 | FEAT-197 | Given the audited action catalog (logins, RBAC changes, configuration changes, secret access, data exports, report generation, RAG/MCP access), When each catalogued action is performed in a test sweep, Then exactly one audit record per action exists with actor, timestamp, action, target, tenant, and outcome — zero misses (audit log completeness). |
| AC-086 | FEAT-197 | Given audit records exist, When any user (including platform admins) attempts to update or delete an audit record via any API or admin surface, Then no such operation exists/succeeds; retention-based expiry is the only removal path and is itself audited. |
| AC-087 | FEAT-197 | Given an audit reviewer, When they filter the audit log by actor, action type, target, and time range and export the result, Then results are complete for the tenant scope and the export itself is audited. |
| AC-088 | FEAT-195 | Given the master key is supplied via env, file, or Vault (KMS SPI), When the master key is rotated, Then data keys are re-wrapped without re-encrypting all payloads, old-key decryption is disabled after rotation completes, and the rotation is audited. |
| AC-089 | FEAT-200 | Given metric governance guardrails, When the full API surface is scanned, Then no endpoint returns individual-ranked productivity data, and every metric response includes its caveats/limitations reference (verified by contract tests). |
| AC-090 | FEAT-199 | Given the security certification checklist, When a release candidate is cut, Then dependency/container scans show no unwaived critical findings, TLS is enforced on all listeners, and the checklist document is complete for that version. |

## 12. Observability

| ID | Feature | Criterion |
|----|---------|-----------|
| AC-091 | FEAT-205 | Given any API request, When it is processed, Then a trace exists spanning the HTTP entry point through DB/Kafka/Redis/LLM calls, with the `traceparent` propagated into produced event envelopes and consumed downstream — verified by sampling 100% in the test environment and asserting every request produced a root span (every API request traced). |
| AC-092 | FEAT-207 | Given the Prometheus scrape endpoint, When scraped, Then key metrics are exposed: HTTP latency/error rates per route, Kafka consumer lag per group, DLQ depth, job durations/outcomes, connector sync results, LLM tokens/cost per provider, cache hit rate, and DB pool utilization. |
| AC-093 | FEAT-206 | Given any log line in any component, When emitted, Then it is structured JSON with timestamp, level, module, tenant (where applicable), and trace/span IDs, and contains no secret material or unredacted prompt content. |
| AC-094 | FEAT-208 | Given the shipped Grafana dashboards, When the Docker Compose stack runs under simulated load, Then all dashboard panels render live data with no broken queries. |
| AC-095 | FEAT-209 | Given a hard dependency (PostgreSQL) is stopped, When probes run, Then readiness fails while liveness passes (no restart loop), and traffic resumes automatically on recovery; Given a soft dependency (an LLM provider) is down, Then the platform reports degraded, not unready. |
| AC-096 | FEAT-210 | Given platform SLO alert rules, When ingestion lag or API error budget burn crosses thresholds in a fault-injection test, Then alerts fire to configured notification channels within the evaluation interval. |

## 13. Traceability

Every P0/P1 feature in `./FeatureCatalog.md` is covered by at least one AC above or by the Definition of Done (section 1), which applies universally. Features whose observable behavior is primarily infrastructural (FEAT-006, FEAT-011, FEAT-036, FEAT-098, FEAT-008/009 hardening) are verified through the DoD checklist plus the phase exit criteria in `./Roadmap.md`.
