# Personas

This document defines the canonical user personas for the Engineering Intelligence Platform (EIP). Every screen, report, agent interaction, and permission decision in the product traces back to one or more of these personas. Personas map to the tenant-scoped RBAC model (roles + fine-grained permissions) defined in the platform core (`/backend/eip-tenancy`); see `../architecture/DomainModel.md` for the `Member`/`Role` entities and `UserJourneys.md` / `UseCases.md` for behavior traceability.

EIP is explicitly **not** an individual-surveillance or stack-ranking tool. All personas below consume team-level or aggregate signals with context, uncertainty, and stated limitations. No persona has a legitimate workflow built on ranking individuals by commit counts, and no screen supports one.

## 1. RBAC roles referenced by personas

| Role | Scope | Representative fine-grained permissions |
|---|---|---|
| `PLATFORM_ADMIN` | Installation (cross-tenant) | `tenant:manage`, `connector:manage`, `secret:rotate`, `llm:configure`, `mcp:manage`, `rbac:manage`, `audit:read`, `system:operate` |
| `TENANT_ADMIN` | Single tenant | `connector:manage` (tenant), `rbac:manage` (tenant), `rag:manage`, `report:schedule`, `audit:read` (tenant) |
| `ENGINEERING_MANAGER` | BusinessUnit / multiple Teams | `metric:read`, `risk:read`, `report:generate`, `agent:invoke`, `dashboard:configure` |
| `TEAM_LEAD` | Team | `metric:read` (team), `report:generate` (team), `agent:invoke` (team-scoped agents), `board:read` |
| `MEMBER` | Team (self + team aggregates) | `metric:read` (team aggregates), `workitem:read`, `report:read` |
| `RELEASE_MANAGER` | Product / Release | `release:read`, `report:generate` (release), `agent:invoke` (Release Notes), `quality:read` |
| `EXECUTIVE_VIEWER` | Organization | `metric:read` (org rollups), `report:read`, `report:generate` (Executive Summary) |
| `SECURITY_AUDITOR` | Tenant or installation | `audit:read`, `secret:audit`, `llm:audit`, `securityfinding:read`, `mcp:audit` |

Roles are additive; a person may hold several (e.g., a Team Lead who is also a Release Manager). All roles are tenant-scoped except `PLATFORM_ADMIN`.

Alignment with the platform role model (see FEAT-004/FEAT-023 in `./FeatureCatalog.md` and FR-120–FR-122 in `./PRD.md`): `PLATFORM_ADMIN` and `TENANT_ADMIN` are built-in roles; the remaining six ship as **role templates** composed from fine-grained permissions on top of the built-in `analyst`/`viewer` bases. Tenant admins may clone and adjust templates (permission preview supported) but cannot grant permissions they do not themselves administer. Persona-to-role mapping below always names the template; deployments may rename templates without affecting this document's traceability, because use cases in `./UseCases.md` reference personas, not role names.

## 2. Persona catalog

### 2.1 Platform Administrator — "Deniz"

- **Profile:** Senior DevOps/platform engineer in central IT. Operates on-prem Kubernetes/OpenShift and Docker Compose environments, Keycloak, PostgreSQL, Kafka. Owns installation, upgrades, and integrations for internal tooling. Comfortable with Helm/Kustomize, Vault, and network policy in air-gapped zones.
- **Goals:** Install and upgrade EIP with zero data loss; connect Jira, GitHub, Confluence, SonarQube, and CI/CD systems; keep sync pipelines healthy; configure local LLMs (Ollama/vLLM) so no data leaves the network; onboard tenants and delegate administration.
- **Pain points today:** Every analytics tool the org tried was SaaS-only or leaked data; connector credentials are scattered in plaintext config; nobody can tell which integration broke or why a dashboard is stale.
- **How EIP helps:** Single on-premise deployment (Docker Compose for pilot, Kustomize overlays for production); Connector SPI with `validate()`, `testConnection()`, `healthCheck()`, checkpointed `incrementalSync`; AES-256-GCM envelope-encrypted secrets with rotation and audited access; OpenTelemetry-based self-observability shipped as Grafana dashboards in `/infra/grafana`.
- **Key screens/reports:** Admin Console (tenants, connectors, secrets, LLM providers, MCP registry), Connector Health dashboard, Sync Checkpoint browser, DLQ inspector, System Observability (Grafana), Audit Log viewer.
- **Permissions:** `PLATFORM_ADMIN`.
- **Usage pattern:** Intensive during install/upgrade windows; otherwise a daily 10-minute health scan of Connector Health and DLQ depth plus alert-driven interventions. Works in the Admin Console and Grafana, rarely in analytics dashboards.
- **Representative quote:** "If it can't run inside our network with our keys, it doesn't run here at all."
- **Key journeys & use cases:** J-01, J-07, J-09 (`./UserJourneys.md`); UC-001, UC-003, UC-005, UC-006, UC-008, UC-015, UC-016, UC-017 (`./UseCases.md`).
- **Success criteria:** First sync from Jira+GitHub completes within the first working session; connector health is green or explains itself; secret rotation takes minutes and breaks nothing; upgrades apply Flyway migrations cleanly; air-gapped LLM answers queries with zero egress.

### 2.2 Engineering Manager — "Mira"

- **Profile:** Manages 3–5 teams (25–40 engineers) delivering a product line. Splits time between planning, stakeholder reporting, and unblocking teams. Uses Jira daily, GitHub occasionally, spreadsheets constantly.
- **Goals:** See delivery health across teams without manually collating Jira exports; detect risks (delays, dependencies, blocked work) before the sprint review; report upward with evidence rather than anecdotes.
- **Pain points today:** Sprint data lives in Jira, code health in SonarQube, deployments in CI — correlating them takes a day per month; blockers surface only when someone escalates; velocity numbers get quoted without context and misused.
- **How EIP helps:** Correlated flow metrics (velocity, throughput, cycle time, lead time, WIP, flow efficiency, blocked time, sprint predictability, scope churn) plus DORA metrics on one dashboard, each with purpose, formula, caveats/limitations, and gaming risks stated inline; the Delivery Risk agent flags at-risk Epics and dependency risk with explanations and source citations.
- **Key screens/reports:** Delivery Health dashboard (multi-team), Sprint dashboard with drill-down to blocked WorkItems, Epic Delivery Risk view, Dependency map, scheduled weekly Delivery Health report (Report Composition agent).
- **Permissions:** `ENGINEERING_MANAGER`.
- **Usage pattern:** 15–30 minutes daily on the Delivery Health dashboard; deep drill-downs twice per sprint (mid-sprint and pre-review); weekly scheduled report consumed asynchronously.
- **Representative quote:** "I don't need more charts — I need to know which of my twelve epics will actually be late, and why."
- **Key journeys & use cases:** J-02; UC-009, UC-010, UC-011, UC-012.
- **Success criteria:** Monthly status prep drops from a day to under an hour; ≥70% of flagged risks judged actionable; teams trust the metrics because caveats are visible; zero incidents of metrics being used to rank individuals.

### 2.3 Team Lead / Scrum Master — "Sam"

- **Profile:** Facilitates ceremonies for one or two teams; half-technical, half-process role. Owns the Board, the Sprint, and the retro follow-ups.
- **Goals:** Run data-informed standups and retros; prepare sprint reviews without spending an evening building slides; keep WIP and blocked time visible to the team itself.
- **Pain points today:** Sprint review decks are hand-built from Jira screenshots; blocked items rot silently; commitment vs. done is argued from memory.
- **How EIP helps:** Sprint and Kanban dashboards (Board, WorkflowState/WIP states, blocked time) refreshed by incremental sync and webhooks; the Sprint Review agent generates a review presentation (goals vs. outcomes, demo list, scope churn, carry-over) grounded via RAG in the team's actual WorkItems, PullRequests, and Deployments, with citations.
- **Key screens/reports:** Team Sprint dashboard, Kanban flow view (cycle time scatter, WIP aging), Blocked Items list, generated Sprint Review presentation in the artifact library.
- **Permissions:** `TEAM_LEAD`.
- **Usage pattern:** Kanban flow view open during standup every morning; Sprint Review agent invoked once per sprint; blocked-items list checked before every planning session.
- **Representative quote:** "The retro should argue about what we do next, not about what the numbers were."
- **Key journeys & use cases:** J-03; UC-009, UC-011.
- **Success criteria:** Sprint review prep under 30 minutes; generated deck needs only light editing; blocked-time trend visible and discussed in every retro; sprint predictability improves quarter over quarter.

### 2.4 Developer — "Arda"

- **Profile:** Senior engineer on a product team. Lives in the IDE, GitHub, and CI. Skeptical of "productivity tooling" for good historical reasons.
- **Goals:** See where team flow actually stalls (review latency, flaky pipelines, unclear stories); trust that the platform will not be used against individuals; get useful artifacts (release notes, docs) without extra toil.
- **Pain points today:** PRs wait days for review with no visibility; incident retrospectives lack linked data; management metrics feel like surveillance.
- **How EIP helps:** Team-level review bottleneck and knowledge concentration (bus factor) signals — explicitly anti-toxic-ranking, aggregate-only; Documentation and Release Notes agents remove writing toil; every metric page states its limitations and gaming risks, which builds trust.
- **Key screens/reports:** Team Kanban flow view, PR/review flow dashboard (team aggregate), Quality dashboard (coverage, code smells, quality gate status), generated Release Notes and Documentation artifacts.
- **Permissions:** `MEMBER`.
- **Usage pattern:** Infrequent and pull-based — visits when the team discusses flow in a retro, when a generated artifact mentions their service, or to sanity-check a metric definition. Adoption is won or lost on trust, not features.
- **Representative quote:** "Show me the formula and what it can't tell you — then I'll believe the chart."
- **Key journeys & use cases:** consumer within J-02/J-03 outcomes; UC-009, UC-011 (as report consumer).
- **Success criteria:** Can verify no individual-ranking view exists; review wait time drops after the team acts on bottleneck data; release notes for their service are generated, accurate, and cite the underlying PullRequests.

### 2.5 Product Manager — "Leyla"

- **Profile:** Owns a Product and its Roadmap; translates Initiatives into Epics and Features; negotiates scope with Engineering Manager and stakeholders.
- **Goals:** Know whether Epics and Initiatives will land when promised; understand scope churn and its causes; communicate roadmap confidence honestly.
- **Pain points today:** Roadmap dates are set by gut feel; slippage is discovered late; dependency risk across teams is invisible until it bites.
- **How EIP helps:** Epic delivery risk and project delay prediction with stated uncertainty; scope churn tracking per Sprint and Epic; Dependency entities correlated across teams; the Executive Summary and Report Composition agents turn this into stakeholder-ready narrative with citations.
- **Key screens/reports:** Roadmap/Initiative view with per-Epic risk badges, Scope Churn report, Dependency risk view, on-demand Epic status narrative.
- **Permissions:** `ENGINEERING_MANAGER` template (metric/risk read + report generation; no admin permissions).
- **Usage pattern:** Weekly roadmap review against the risk view; ad-hoc Epic status narratives before stakeholder meetings; scope churn checked at every sprint boundary.
- **Representative quote:** "A date without a confidence level is a promise I can't keep."
- **Key journeys & use cases:** J-02 (risk drill-down variant); UC-009, UC-010, UC-011.
- **Success criteria:** Slippage flagged at least one sprint earlier than today; roadmap conversations reference shared data; stakeholder updates generated in minutes.

### 2.6 Release Manager — "Jonas"

- **Profile:** Coordinates releases across teams for a regulated product. Owns the go/no-go call, the release checklist, and the communication around each Release.
- **Goals:** A defensible release readiness picture (quality gates, open Bugs, escaped defects, pending Deployments, unresolved SecurityFindings); release notes produced from actual merged work, not tribal memory.
- **Pain points today:** Readiness is a spreadsheet assembled from five tools the night before; release notes are inconsistent and miss changes; nobody records why a release was approved.
- **How EIP helps:** Release readiness score composed from quality (QualityGate status, coverage, escaped defects, bug aging, security finding aging), flow, and CI/CD signals with drill-down to evidence; the Release Notes agent composes notes from PullRequests, WorkItems, and Deployments in the Release scope, stored as a GeneratedReport with full provenance.
- **Key screens/reports:** Release Readiness dashboard, Release detail view (linked Builds, Pipelines, Deployments, Artifacts, Environments), generated Release Notes, go/no-go evidence export.
- **Permissions:** `RELEASE_MANAGER`.
- **Usage pattern:** Cyclical, peaking in the week before each Release: daily readiness checks, one release-notes generation per release, evidence export at go/no-go. Quiet between cycles.
- **Representative quote:** "I sign the go decision — so every red and green on that screen has to trace to evidence."
- **Key journeys & use cases:** J-04; UC-009, UC-010, UC-011, UC-012.
- **Success criteria:** Go/no-go meeting runs off one screen; release notes generation under 5 minutes with >90% of entries needing no edit; audit trail satisfies compliance review.

### 2.7 SRE / Ops Engineer — "Priya"

- **Profile:** Runs production for several Services; on-call rotation; owns SLOs, alerts, and incident review. Fluent in Prometheus, Grafana, OpenTelemetry.
- **Goals:** Correlate Incidents with the Deployments and changes that preceded them; track MTTR, change failure rate, incident frequency/impact, SLO health, and alert noise; feed operational reality back into delivery planning.
- **Pain points today:** "What changed?" during an incident means grepping CI logs; post-incident reviews lack linked evidence; ops signals never reach sprint planning.
- **How EIP helps:** Kubernetes/OpenShift, Prometheus, Grafana, and OpenTelemetry (OTLP intake) connectors correlate Deployment, Incident, Alert, LogReference, and TraceReference entities on a timeline; the Incident Analysis agent drafts impact analyses citing the correlated data; DORA and ops metrics appear beside flow metrics so operability influences planning.
- **Key screens/reports:** Ops dashboard (incident frequency/impact, SLO health, alert noise), Incident timeline view with deployment correlation, Service detail view, generated Incident Analysis reports.
- **Permissions:** `TEAM_LEAD` scope over owned Services plus `agent:invoke` for Incident Analysis (granted via an ops role template cloned from `TEAM_LEAD`).
- **Usage pattern:** Alert-driven during incidents (timeline view under time pressure, often at night); weekly SLO/alert-noise review; monthly operability readout to Engineering Managers.
- **Representative quote:** "At 2 a.m. I have one question: what changed? Answer that fast and you've earned your keep."
- **Key journeys & use cases:** J-06; UC-005 (ops connectors, with the admin), UC-009, UC-011, UC-014.
- **Success criteria:** "What changed before this incident" answered in under a minute; MTTR trend visible per Service; alert-noise data drives at least one alert-tuning action per quarter.

### 2.8 CISO / Security Officer — "Helena"

- **Profile:** Accountable for security and compliance of internal platforms. Reviews new tools for data handling, especially anything touching AI. Runs periodic audits.
- **Goals:** Verify EIP's AI never exfiltrates data (on-prem/air-gapped LLMs); audit every LLM call, secret access, and MCP tool invocation; confirm tenant isolation and RBAC enforcement; track SecurityFinding aging as a metric.
- **Pain points today:** AI tools are adopted faster than they can be reviewed; audit evidence is assembled manually; secret handling in integration tools is historically poor.
- **How EIP helps:** Every LLM call is audited (prompt, model, tokens, cost, latency; prompts redacted per policy); secrets use AES-256-GCM envelope encryption with a pluggable KMS SPI, are masked in UI, and every access is audited; MCP client/server capabilities are allow-listed with per-capability RBAC and audit; Postgres RLS enforces row-level tenant isolation; the Security Review agent assists audits.
- **Key screens/reports:** Audit Log viewer (filterable by actor, action, entity, tenant), LLM Usage audit view, Secret Access audit view, MCP invocation audit, SecurityFinding aging dashboard, exportable audit evidence packs.
- **Permissions:** `SECURITY_AUDITOR` (read/audit only; no configuration rights — separation of duties from `PLATFORM_ADMIN`).
- **Usage pattern:** Quarterly deep audits plus spot checks after any new connector, LLM provider, or MCP capability is enabled; subscribes to audit anomaly alerts rather than watching dashboards.
- **Representative quote:** "'The AI is on-prem' is a claim. The egress log and the call audit are the proof."
- **Key journeys & use cases:** J-08; UC-004, UC-018; audits the outcomes of UC-002, UC-003, UC-013, UC-014, UC-015.
- **Success criteria:** Quarterly audit evidence exported in under an hour; zero unaudited LLM calls or secret accesses; air-gap posture verifiable from configuration and egress logs.

### 2.9 VP Engineering / CTO — "Kenji"

- **Profile:** Executive accountable for engineering across the Organization. Consumes information in 15-minute windows; needs trends, outliers, and narrative, not dashboards of raw charts.
- **Goals:** Quarterly view of delivery health, DORA performance, quality and technical debt trends, incident posture, and top risks across BusinessUnits; confidence that numbers are honest (with caveats), not gamed.
- **Pain points today:** Board updates are assembled from slideware of varying rigor; comparisons across units are apples-to-oranges; no early warning on systemic problems like rising technical debt ratio.
- **How EIP helps:** Organization-level rollups on consistent metric definitions; the Executive Summary agent generates a quarterly engineering health report — narrative plus charts, every claim cited to underlying Metrics and with limitations stated; technical debt ratio and security finding aging tracked as first-class trends.
- **Key screens/reports:** Executive Overview dashboard (org rollups, trends, outliers), quarterly Engineering Health report (generated, scheduled), Top Risks summary.
- **Permissions:** `EXECUTIVE_VIEWER`.
- **Usage pattern:** Consumes the scheduled quarterly report end-to-end; glances at the Executive Overview before leadership meetings; almost never drills below the second citation level — the narrative must carry the load.
- **Representative quote:** "Give me three trends, one surprise, and the honest error bars."
- **Key journeys & use cases:** J-05; UC-009 (org grain), UC-011, UC-012 (as consumer).
- **Success criteria:** Quarterly report requires no manual data collation; identifies at least one systemic issue per quarter early enough to act; leadership discussions cite EIP data with its caveats.

### 2.10 Agile Coach — "Rosa"

- **Profile:** Works across teams to improve delivery practice. Runs maturity assessments, coaches Scrum Masters, and designs experiments (WIP limits, review SLAs).
- **Goals:** Compare flow health across teams *without* creating league tables; measure whether process experiments actually changed cycle time, flow efficiency, or predictability; ground coaching in evidence.
- **Pain points today:** Before/after evidence for process changes is anecdotal; each team's Jira configuration makes cross-team comparison unreliable; metric misuse by management poisons adoption.
- **How EIP helps:** Normalized WorkItem model and WorkflowState mapping make flow metrics comparable across differently configured Boards; every metric ships with caveats/limitations and gaming risks she can teach from; Team Health signals (load balance, review bottlenecks, knowledge concentration) are team-level only and explicitly anti-toxic-ranking; the Team Health agent summarizes patterns with uncertainty stated.
- **Key screens/reports:** Cross-team Flow comparison (opt-in, anonymizable team labels), Cycle time/flow efficiency trend views, Sprint predictability history, Team Health summary, experiment before/after report.
- **Permissions:** `ENGINEERING_MANAGER` template (multi-team metric read) without `dashboard:configure` for teams she does not own; granted per engagement by the `TENANT_ADMIN`.
- **Usage pattern:** Engagement-based: baseline measurement at kickoff, weekly experiment tracking during the engagement, before/after report at close; uses simulation-mode tenants for training workshops.
- **Representative quote:** "The moment a flow metric becomes a target for a team ranking, it stops measuring flow."
- **Key journeys & use cases:** J-02 (comparison variant), J-09 (training); UC-009, UC-010 (secondary), UC-011, UC-016.
- **Success criteria:** Every coaching engagement has a baseline and follow-up measurement; at least one experiment per quarter shows a measured flow improvement; teams volunteer for measurement rather than resisting it.

## 3. Persona-to-capability matrix

Capabilities are the platform's major functional areas (see `../architecture/` and the implementation phases in the design brief). ● = primary daily use, ○ = occasional/secondary use, A = administers/configures, blank = no access or no need.

| Capability | Platform Admin | Eng. Manager | Team Lead/SM | Developer | Product Manager | Release Manager | SRE/Ops | CISO/Security | VP Eng/CTO | Agile Coach |
|---|---|---|---|---|---|---|---|---|---|---|
| Connector setup & sync health (`eip-connectors`, `eip-ingestion`) | A | ○ | | | | | ○ | ○ | | |
| Flow & sprint metrics (`eip-analytics`) | | ● | ● | ○ | ● | ○ | ○ | | ○ | ● |
| DORA metrics | | ● | ○ | ○ | | ● | ● | | ● | ○ |
| Quality & technical debt metrics | | ● | ○ | ● | ○ | ● | ○ | ○ | ● | ○ |
| Delivery risk & dependency views | | ● | ○ | | ● | ● | | | ● | ○ |
| Ops/incident metrics & correlation | | ○ | | ○ | | ○ | ● | ○ | ○ | |
| Team health signals (team-level) | | ● | ● | ○ | | | | | | ● |
| Dashboards (React frontend, ECharts) | ○ | ● | ● | ● | ● | ● | ● | ○ | ● | ● |
| Report generation (`eip-reports`, agents) | | ● | ● | ○ | ● | ● | ● | ○ | ● | ● |
| Report scheduling & artifact library | A | ● | ○ | | ○ | ● | ○ | | ● | ○ |
| AI agents — invoke (`eip-ai`) | ○ | ● | ● | ○ | ● | ● | ● | ○ | ● | ● |
| LLM provider configuration | A | | | | | | | ○ (audit) | | |
| RAG knowledge base management | A / A (tenant admin) | | | ○ | | | | ○ (audit) | | |
| MCP client/server registry | A | | | | | | | ○ (audit) | | |
| Tenancy & RBAC administration (`eip-tenancy`) | A | | | | | | | ○ (audit) | | |
| Secrets & rotation | A | | | | | | | ○ (audit) | | |
| Audit log & AI usage audit | ○ | | | | | | | ● | | |
| Simulation/mock mode | A | ○ | ○ | | ○ | | | | ○ | ○ |
| Self-observability (OTel → Grafana) | ● | | | | | | ○ | | | |

## 4. Persona value by roadmap phase

Rollout guidance: which persona first receives day-to-day value at each implementation phase (canonical roadmap, Phases 0–5). Use this to sequence onboarding and training per audience — do not onboard a persona before their phase, or first impressions will be of an empty product.

| Phase | Personas activated | First value delivered |
|---|---|---|
| 0 — Foundations | Platform Administrator; CISO/Security Officer (audit core) | Deployable stack, tenancy/RBAC/secrets in place, audit log live from day one |
| 1 — Ingestion core + first connectors | Platform Administrator (fully); Engineering Manager (early data validation) | Jira + GitHub synced, normalized model browsable, simulation connector for demos |
| 2 — Analytics & dashboards | Engineering Manager, Team Lead/Scrum Master, Developer, Product Manager, SRE/Ops, Agile Coach | Flow/DORA/quality/ops dashboards with metric definitions and drill-down |
| 3 — AI core | Team Lead (Sprint Review agent), Release Manager (Release Notes agent), Product Manager (Delivery Risk), CISO (LLM audit) | First generated, cited artifacts; RAG over Confluence; local LLM operation |
| 4 — Full agent suite + MCP + outputs | VP Engineering/CTO (Executive Summary, scheduling), SRE (Incident Analysis agent), all report consumers | Scheduled reports, presentations, diagrams, notification delivery, MCP tools |
| 5 — Enterprise hardening | Platform Administrator, CISO (certification checklist, HA, backup/restore) | Production-grade K8s/OpenShift GA, upgrade and recovery confidence |

## 5. Screen inventory by persona (frontend planning input)

Consolidated list of distinct screens implied by the persona definitions; each screen names its primary personas and the earliest phase it can exist. This table is the seed for frontend information architecture.

| Screen | Primary personas | Earliest phase |
|---|---|---|
| Admin Console: tenants, connectors, secrets | Platform Admin | 0–1 |
| Admin Console: LLM providers, model routing, RAG corpora, MCP registry | Platform Admin | 3–4 |
| Access management (roles, permissions, permission preview) | Platform Admin, Tenant Admin | 0 |
| Connector Health, Sync Checkpoints, DLQ inspector | Platform Admin | 1 |
| Delivery Health dashboard (multi-team) | Engineering Manager, Agile Coach | 2 |
| Sprint dashboard + blocked-items drill-down | Team Lead/SM, Engineering Manager | 2 |
| Kanban flow view (cycle time scatter, WIP aging) | Team Lead/SM, Developer, Agile Coach | 2 |
| Quality dashboard (coverage, smells, gates, debt) | Developer, Release Manager, Eng. Manager | 2 |
| Risk view (epic risk, delay prediction, dependencies) | Product Manager, Eng. Manager | 3 |
| Release Readiness + Release detail | Release Manager | 3 |
| Ops dashboard + Incident timeline | SRE/Ops | 2 (metrics) / 4 (agent) |
| Executive Overview | VP Eng/CTO | 4 |
| Report Generation Center + artifact library | all report producers/consumers | 3 |
| Audit views (general, LLM usage, secret access, MCP, RAG) | CISO/Security Officer | 0 (general) / 3–4 (AI/MCP) |
| RAG console (test queries, corpus status) | Platform/Tenant Admin | 3 |
| Simulation tenant management | Platform Admin | 1 |

## 6. Anti-personas and misuse boundaries

EIP explicitly does not serve the following intents, and the product enforces the boundary rather than merely discouraging it:

| Anti-persona / intent | Why out of scope | Enforcement in product |
|---|---|---|
| "Performance ranker" — a manager seeking per-person productivity scores, commit counts, or leaderboards | Anti-goal of the platform; such metrics are invalid and corrosive | No individual-ranking view, metric, or export exists (PRD FR-057); team-health metrics computed at team grain only (FR-055); release-blocking ethics guardrail (NFR-071) |
| "Data exfiltrator" — anyone routing engineering data to external SaaS AI without approval | Violates on-prem/air-gap posture | LLM providers are registered and audited; unapproved endpoints are inactive by default; egress observable via OTel dashboards (NFR-051) |
| "Shadow admin" — a power user accumulating both configuration and audit rights | Breaks separation of duties | SoD constraint blocks `SECURITY_AUDITOR` + `secret:rotate` combinations (see UC-002 in `./UseCases.md`) |
| "Cross-tenant analyst" — a consultant comparing tenants' data inside one deployment | Violates tenant isolation | Postgres RLS on tenant_id; no cross-tenant query path except platform-level operational metadata for `PLATFORM_ADMIN` |

## 7. Cross-cutting persona requirements

1. **Trust and anti-surveillance (all personas).** Every metric surface shows the metric's purpose, formula, inputs, grain, caveats/limitations, and gaming risks. No screen ranks named individuals. This is a product invariant, testable in UI acceptance criteria.
2. **Tenant isolation (all personas).** No persona except `PLATFORM_ADMIN` ever sees cross-tenant data; enforcement is row-level (tenant_id + Postgres RLS), not UI-level.
3. **Citations (all AI consumers).** Generated narratives (Sprint Review, Release Notes, Executive Summary, Incident Analysis) always carry source citations to canonical entities so any persona can verify claims.
4. **Separation of duties (Deniz vs. Helena).** The Platform Administrator configures; the Security Officer audits. `SECURITY_AUDITOR` deliberately lacks write permissions, and `PLATFORM_ADMIN` actions are themselves audited and reviewable by Helena.
5. **Air-gap parity (Deniz, Helena, all consumers).** Every capability above must function with local LLMs (Ollama/vLLM) and no external egress; SaaS LLM providers are optional, per-tenant, and off by default.
6. **Traceability.** Persona needs trace forward to journeys (`./UserJourneys.md`), formal use cases (`./UseCases.md`), features (`./FeatureCatalog.md`), and requirements (`./PRD.md`); the per-persona "Key journeys & use cases" bullets and the matrix in section 3 are the binding cross-references and must be updated together with those documents.
