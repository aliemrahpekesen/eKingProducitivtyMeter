# Founder Decisions — Binding Planning Constraints

> Date: 2026-07-08 · Status: **FROZEN founder decisions** · Scope: planning constraints only.
> This record captures four founder decisions and binds all future *planning* to them. It does **not** modify the architecture, roadmap, PRD, ADRs, or any `/docs` spec — because, as shown below, three of the four are already the documented architecture and the fourth is a segment-focus refinement. Nothing here expands scope or redesigns anything.

## The decisions

### 1. Target customer (planning refinement)
**Large regulated enterprises — banks, airlines, insurance, telecom, energy and similar — with ~500–5,000 engineers.**

- **Status vs specs:** *refines* the existing segment. [Vision §2.1](../../docs/vision/Vision.md) already targets regulated enterprises (banking, insurance, telecom, healthcare, energy, public sector, 200–10,000+ engineers). The founder narrows the *initial* focus to the 500–5,000 band.
- **Planning effect:** the first design partner and the first vertical slice are optimized for this profile (heavy compliance, on-prem/air-gap, security-review-gated procurement). No spec change; this sharpens *who we build the first demo for*, consistent with the [Sprint-01 Execution Plan](../Sprint01ExecutionPlan.md) §8 "commit to one design-partner profile."

### 2. Product positioning (confirmatory — already the spec)
**On-prem Enterprise SDLC Intelligence Platform focused on deterministic Engineering Productivity Intelligence; AI is an assistive, cited, non-authoritative layer.**

- **Status vs specs:** *already fully documented.* [Vision §3–§4/§9](../../docs/vision/Vision.md), [PRD §2](../../docs/product/PRD.md), and ImplementationReadiness principle 1 ("AI is additive and never trusted; every non-generative capability works with zero LLM providers configured; generative failure never blocks ingestion/analytics/dashboards/RBAC"). The deterministic engine is the source of truth; AI outputs are RAG-grounded, citation-bearing, and validation-gated (FR-087/088).
- **Planning effect:** none required — this confirms the existing direction. No change.

### 3. Integration strategy — vendor-neutral, self-managed **and** cloud (confirmatory — already the spec)
**Vendor-neutral architecture supporting both self-managed and cloud ecosystems** across Jira DC/Cloud, Bitbucket DC/Cloud, GitHub Enterprise Cloud/Server, GitLab Self-Managed/Cloud, SonarQube/SonarCloud, Jenkins, GitHub Actions, GitLab CI, Azure DevOps, OpenShift, Kubernetes, and beyond.

- **Status vs specs:** *already the architecture.* The Connector SPI ([PRD FR-001](../../docs/product/PRD.md); `eip-connectors`) is vendor-neutral by construction (per-tool connectors, JSON-Schema config, `validate`/`testConnection`/`healthCheck`/`fullSync`/`incrementalSync`/webhook/simulation). [ConnectorFramework.md](../../docs/engineering/ConnectorFramework.md) **already specifies the DC-vs-Cloud split explicitly** — e.g. Jira "API token (Basic, Cloud), PAT (Data Center)"; Bitbucket "Cloud and Data Center have different APIs (2.0 vs 1.0 shapes) — the connector abstracts both behind one stream set." Data-Center/Server and Cloud are connector *variants/config* behind one canonical model, not architectural forks.
- **Planning effect:** the vertical-slice connectors are built **behind the vendor-neutral SPI, supporting both DC/Cloud variants** — but *instantiated first for the specific variant the first design partner runs* (a regulated enterprise is most often Jira DC + Bitbucket DC / GitLab Self-Managed + SonarQube). This **broadens** the [Sprint-01 Execution Plan](../Sprint01ExecutionPlan.md) §4/§6 "self-managed-first" emphasis to "vendor-neutral, both variants, design-partner's variant first." No architecture change — the SPI already requires this.

### 4. AI strategy — provider abstraction mandatory (confirmatory — already the spec)
**AI provider abstraction is mandatory; never couple the platform to a specific LLM vendor.** Must support OpenAI, Claude, Gemini, Azure OpenAI, GitHub Copilot Enterprise, Ollama, vLLM, NVIDIA NIM, and enterprise-hosted inference endpoints.

- **Status vs specs:** *already the architecture.* The **LLM provider SPI** ([PRD FR-084](../../docs/product/PRD.md); [Vision §4.3](../../docs/vision/Vision.md); ADR-006) already mandates provider abstraction over "Ollama, vLLM (OpenAI-compatible), OpenAI-compatible generic, Anthropic-compatible, and custom enterprise endpoints," with per-tenant/per-agent routing, fallbacks, and audited calls. Every named provider maps onto the existing abstraction: **OpenAI / Azure OpenAI / Gemini / NVIDIA NIM / Copilot Enterprise → OpenAI-compatible or custom endpoint; Claude → Anthropic-compatible; Ollama / vLLM → local.** No provider is coupled; all go through the SPI.
- **Planning effect:** the vertical slice's single AI narrative is implemented **through the LLM provider SPI only** — a thin adapter, local model by default, no direct vendor coupling anywhere. No architecture change — the SPI already requires this.

## Net effect on the plan

| Decision | Needs a change? | What binds now |
|---|---|---|
| 1 · Target 500–5,000 regulated | Planning focus only | First design partner + first slice optimized for this profile |
| 2 · On-prem, deterministic-first, AI-assistive | No — already the spec | Confirms direction; no change |
| 3 · Vendor-neutral, DC **and** Cloud | No — already the SPI | Slice connectors behind the SPI, both variants, design-partner's variant first |
| 4 · Mandatory AI provider abstraction | No — already the SPI | Slice AI narrative through the LLM provider SPI only |

**Standing implementation rule (all future work):** every source-tool integration goes through the vendor-neutral **Connector SPI**; every AI call goes through the vendor-neutral **LLM provider SPI**; the **deterministic engine is always the source of truth** and AI is assistive/cited/validation-gated/local-first. No direct coupling to any tool vendor or any LLM vendor, ever — this is now a founder-level, non-negotiable constraint on top of the existing architecture.

## Related documents

- [../Sprint01ExecutionPlan.md](../Sprint01ExecutionPlan.md) · [product-planning/Sprint01PlanningWorkshop.md](./Sprint01PlanningWorkshop.md) · [../executive-architecture-audit/Sprint00ExecutiveArchitectureAudit.md](../executive-architecture-audit/Sprint00ExecutiveArchitectureAudit.md)
- Sources of record (unchanged): [../../docs/vision/Vision.md](../../docs/vision/Vision.md) · [../../docs/product/PRD.md](../../docs/product/PRD.md) · [../../docs/engineering/ConnectorFramework.md](../../docs/engineering/ConnectorFramework.md) · [../../docs/product/FeatureCatalog.md](../../docs/product/FeatureCatalog.md)
