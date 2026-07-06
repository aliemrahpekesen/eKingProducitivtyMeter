# Engineering Intelligence Platform (EIP)

**An on-premise, AI-native Engineering Intelligence Platform for enterprise software organizations.**

EIP integrates with enterprise SDLC tools — Jira, Confluence, GitHub, GitLab, Bitbucket, SonarQube, Artifactory, Kubernetes/OpenShift, Docker registries, Prometheus, Grafana, OpenTelemetry, CI/CD systems, and custom internal project/demand tools — to collect, normalize, correlate, and analyze engineering data. It measures productivity, delivery health, sprint/kanban flow, release readiness, code quality, operational maturity, blockers, risks, delays, dependencies, incidents, and technical debt, and generates dashboards, reports, and AI-composed narrative outputs (sprint reviews, release notes, executive summaries, and more).

> **Repository status:** This repository currently contains the **product specification workspace** — the complete documentation set that guides implementation. Application source code is intentionally not created yet; it will be built phase by phase according to [docs/implementation/PhaseBasedImplementationPlan.md](docs/implementation/PhaseBasedImplementationPlan.md).

## Product purpose

Enterprise engineering organizations run dozens of disconnected tools. Each tool answers questions about itself; none answers questions about the *organization*: Is this release ready? Which epics are at risk? Where does flow break down? What is our real operational maturity? Answering these today requires manual data archaeology, spreadsheets, and gut feel.

EIP exists to answer those questions continuously, on-premise, and with evidence:

- **Correlation over collection** — one normalized engineering data model spanning work items, code, pipelines, deployments, quality, and operations, linked across tools.
- **On-premise sovereignty** — deployable in strict enterprise networks, air-gap capable, no SaaS dependencies except *optional, configurable* LLM providers (local LLMs fully supported).
- **AI-native insight** — a governed agentic backend (18 specialized agents), RAG over enterprise knowledge, and MCP integration, all auditable and permission-aware.
- **Humane metrics** — multi-signal analytics with explicit context, uncertainty, and limitations. Team-level insight, not individual surveillance or stack ranking.
- **Enterprise-grade operations** — multi-tenant, secure-by-design, asynchronous and scalable, fully observable with the same OpenTelemetry/Prometheus/Grafana stack it integrates with.

## Planned technology stack

| Concern | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.x (modular monolith + deployable workers) |
| Database | PostgreSQL 16 (+ pgvector), Flyway migrations, row-level tenant isolation |
| Messaging | Apache Kafka (KRaft) |
| Cache / locks | Redis 7 |
| Vector store | pgvector (default), Qdrant (pluggable) |
| Object storage | S3-compatible (MinIO on-prem default) |
| AI | LLM provider SPI (Ollama, vLLM, OpenAI-/Anthropic-compatible, custom), RAG, MCP client + server |
| Frontend | React 18 + TypeScript + Vite |
| Identity | OIDC (Keycloak default, pluggable enterprise IdP), RBAC |
| Observability | OpenTelemetry, Prometheus, Grafana |
| Deployment | Docker Compose (demo/eval), Kubernetes / OpenShift (production) |

## Repository structure

```
/docs                      Product specification workspace (this deliverable)
  /vision                  Why the product exists and where it is going
  /product                 What we build: PRD, personas, journeys, features, roadmap
  /architecture            How the system is shaped: C4 views, domain model, security
  /ai                      Agentic AI, RAG, and MCP architectures
  /engineering             How we implement: backend, frontend, DB, connectors, events, APIs
  /infrastructure          How we run it: local dev, Docker Compose, Kubernetes/OpenShift
  /testing                 How we prove it works
  /operations              How operators run it in production
  /implementation          Phase-based master build plan

# Created in later phases (see implementation plan):
/backend                   Spring Boot modular monolith (Gradle multi-module)
/frontend                  React admin console and dashboards
/infra                     Docker Compose, Kubernetes/OpenShift manifests, Grafana dashboards
/simulation                Simulated enterprise data packs for demo and testing
/scripts                   Developer and operator tooling
```

## Documentation map

### Vision & Product
| Document | Purpose |
|---|---|
| [docs/vision/Vision.md](docs/vision/Vision.md) | Problem, vision, strategic pillars, differentiation, non-goals |
| [docs/product/PRD.md](docs/product/PRD.md) | Functional (FR-XXX) and non-functional (NFR-XXX) requirements, scope, release criteria |
| [docs/product/Personas.md](docs/product/Personas.md) | Personas from developer to CTO, mapped to roles and capabilities |
| [docs/product/UserJourneys.md](docs/product/UserJourneys.md) | End-to-end journeys from installation to executive reporting |
| [docs/product/FeatureCatalog.md](docs/product/FeatureCatalog.md) | Complete feature inventory (FEAT-XXX) with priority and phase |
| [docs/product/UseCases.md](docs/product/UseCases.md) | Formal use cases (UC-XXX) with flows and exception paths |
| [docs/product/AcceptanceCriteria.md](docs/product/AcceptanceCriteria.md) | Given/When/Then criteria (AC-XXX) for P0/P1 features |
| [docs/product/Roadmap.md](docs/product/Roadmap.md) | Phase 0–5 roadmap with exit criteria and demo milestones |

### Architecture
| Document | Purpose |
|---|---|
| [docs/architecture/ArchitectureOverview.md](docs/architecture/ArchitectureOverview.md) | Architectural drivers, C4 context/containers, module map, ADR summary |
| [docs/architecture/DomainModel.md](docs/architecture/DomainModel.md) | Normalized engineering domain model across all bounded contexts |
| [docs/architecture/ComponentModel.md](docs/architecture/ComponentModel.md) | Component-level design of every backend module |
| [docs/architecture/DataFlow.md](docs/architecture/DataFlow.md) | End-to-end flows: sync, normalization, analytics, RAG, reports |
| [docs/architecture/DeploymentModel.md](docs/architecture/DeploymentModel.md) | Topologies from single-node demo to multi-AZ OpenShift, HA/DR |
| [docs/architecture/SecurityModel.md](docs/architecture/SecurityModel.md) | Threat model, RBAC, secrets, AI security, audit, compliance mapping |
| [docs/architecture/ObservabilityModel.md](docs/architecture/ObservabilityModel.md) | Self-observability: metrics catalog, tracing, dashboards, SLOs |

### AI
| Document | Purpose |
|---|---|
| [docs/ai/AgentArchitecture.md](docs/ai/AgentArchitecture.md) | Agent runtime, all 18 agents, LLM provider SPI, guardrails, evals |
| [docs/ai/RAGArchitecture.md](docs/ai/RAGArchitecture.md) | RAG pipeline, permission-aware retrieval, vector store SPI, citations |
| [docs/ai/MCPArchitecture.md](docs/ai/MCPArchitecture.md) | EIP as MCP client and MCP server, security and audit model |

### Engineering
| Document | Purpose |
|---|---|
| [docs/engineering/BackendPlan.md](docs/engineering/BackendPlan.md) | Module layout, conventions, resilience, outbox, jobs, testing layers |
| [docs/engineering/FrontendPlan.md](docs/engineering/FrontendPlan.md) | SPA architecture, full screen inventory, dashboards, schema-driven forms |
| [docs/engineering/DatabasePlan.md](docs/engineering/DatabasePlan.md) | PostgreSQL physical design, partitioning, RLS, migrations, pgvector |
| [docs/engineering/ConnectorFramework.md](docs/engineering/ConnectorFramework.md) | Connector SPI, sync engine, resilience, simulation mode, full connector catalog |
| [docs/engineering/EventModel.md](docs/engineering/EventModel.md) | Event envelope, topic catalog, schema evolution, outbox, DLQ policy |
| [docs/engineering/APIDesign.md](docs/engineering/APIDesign.md) | REST conventions and the complete /api/v1 endpoint catalog |

### Infrastructure, Testing, Operations, Implementation
| Document | Purpose |
|---|---|
| [docs/infrastructure/LocalDevelopment.md](docs/infrastructure/LocalDevelopment.md) | Developer environment, day-1 journey, make targets, troubleshooting |
| [docs/infrastructure/DockerCompose.md](docs/infrastructure/DockerCompose.md) | Compose deployment spec for demo/eval/small installs, air-gap procedure |
| [docs/infrastructure/KubernetesOpenShift.md](docs/infrastructure/KubernetesOpenShift.md) | Kustomize strategy, workloads, OpenShift SCC/Routes, production checklist |
| [docs/testing/TestingStrategy.md](docs/testing/TestingStrategy.md) | Test pyramid, contract tests, golden analytics datasets, AI evals, E2E |
| [docs/operations/OperationsGuide.md](docs/operations/OperationsGuide.md) | Runbooks, "Start" activation sequence, backup/restore, upgrades |
| [docs/implementation/PhaseBasedImplementationPlan.md](docs/implementation/PhaseBasedImplementationPlan.md) | The master build plan: epics, stories, exit criteria, first 10 PRs |

## Recommended implementation order

Read in this order before writing code:

1. **Orient** — `Vision.md` → `PRD.md` → `Roadmap.md`
2. **Understand the shape** — `ArchitectureOverview.md` → `DomainModel.md` → `ComponentModel.md` → `DataFlow.md`
3. **Understand the contracts** — `EventModel.md` → `ConnectorFramework.md` → `APIDesign.md` → `DatabasePlan.md`
4. **Understand the constraints** — `SecurityModel.md` → `ObservabilityModel.md` → `TestingStrategy.md`
5. **Build** — follow `PhaseBasedImplementationPlan.md` phase by phase, starting with its "first 10 pull requests" list.

## Next development phases

| Phase | Focus | Outcome |
|---|---|---|
| **0 — Foundations** | Monorepo scaffolding, CI, tenancy, RBAC, audit, secrets, OpenAPI, observability, Docker Compose dev stack | A secure, observable, empty platform that runs locally |
| **1 — Ingestion core** | Connector SPI, sync engine, Kafka pipeline, Jira + GitHub + simulation connectors, normalized model v1 | Real and simulated engineering data flowing into the canonical model |
| **2 — Analytics & dashboards** | Metric engine, flow/DORA/quality metrics, core dashboards, GitLab/SonarQube/CI-CD/Prometheus connectors | Teams see trustworthy productivity and delivery insight |
| **3 — AI core** | LLM provider SPI, RAG pipeline, first agents (Sprint Review, Release Notes, Delivery Risk), report engine | First AI-generated, cited, versioned outputs |
| **4 — Full agent suite & MCP** | All 18 agents, MCP client/server, diagrams and executive outputs, scheduling, notifications | The full report generation center |
| **5 — Enterprise hardening** | Kubernetes/OpenShift GA, HA, performance, security certification, backup/restore, remaining connectors | Production-ready 1.0 |

Each phase ends with a runnable, demonstrable vertical slice — see the exit criteria and demo scripts in the [implementation plan](docs/implementation/PhaseBasedImplementationPlan.md).

## Contributing to the specification

The documentation set is the system of record for product and architecture decisions. Changes to decisions recorded here (stack, module boundaries, event contracts, security model) go through the ADR process described in `docs/architecture/ArchitectureOverview.md`. Keep documents cross-consistent: entity names come from `DomainModel.md`, topic names from `EventModel.md`, feature IDs from `FeatureCatalog.md`.
