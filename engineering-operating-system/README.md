# Engineering Operating System (EOS) — Index

The EOS is the binding governance layer for implementing the Engineering Intelligence Platform (EIP). It exists so that dozens of AI engineering agents and human engineers can work in parallel for months without architectural drift. The product specification under [/docs](../docs/) defines **what** to build; the EOS defines **how** it gets built — and both are enforced, not advisory.

Start here: [EngineeringOperatingSystem.md](EngineeringOperatingSystem.md) (the constitution). Every session bootstrap: [/CLAUDE.md](../CLAUDE.md).

## Reading order for a new engineering agent

1. [/CLAUDE.md](../CLAUDE.md) — session operating manual (always first)
2. [EngineeringOperatingSystem.md](EngineeringOperatingSystem.md) — the operating model and the ten laws
3. [AIEngineeringGuide.md](AIEngineeringGuide.md) — day-to-day working discipline
4. Your role card in [AgentResponsibilities.md](AgentResponsibilities.md)
5. [DevelopmentLifecycle.md](DevelopmentLifecycle.md) + [DefinitionOfReady.md](DefinitionOfReady.md) + [DefinitionOfDone.md](DefinitionOfDone.md)
6. The checklists your task's change class triggers ([QualityGatePolicy.md](QualityGatePolicy.md) tells you which)

## Document map

### Operating model & lifecycle
| Document | Purpose |
|---|---|
| [EngineeringOperatingSystem.md](EngineeringOperatingSystem.md) | Constitution: operating model, tiers, the ten laws, how all pieces fit |
| [DevelopmentLifecycle.md](DevelopmentLifecycle.md) | Phase → sprint → task → PR → merge → done; task states; escalation SLAs |
| [SprintExecutionGuide.md](SprintExecutionGuide.md) | Sprint planning, parallel lanes, disjoint write-sets, status, replanning |
| [SprintReviewGuide.md](SprintReviewGuide.md) | Demos vs phase exit criteria, DoD audits, sprint metrics, retro protocol |

### AI collaboration model
| Document | Purpose |
|---|---|
| [AIEngineeringGuide.md](AIEngineeringGuide.md) | Day-in-the-life manual for AI engineering agents; anti-drift countermeasures |
| [AIAgentCatalog.md](AIAgentCatalog.md) | The 21 engineering roles: catalog, team topology, per-phase staffing |
| [AgentResponsibilities.md](AgentResponsibilities.md) | Full role cards (responsibilities, authority, gates, escalation) + RACI |
| [AgentCommunicationProtocol.md](AgentCommunicationProtocol.md) | Artifact-mediated communication: templates, addressing, SLAs, arbitration |
| [ContextManagementStrategy.md](ContextManagementStrategy.md) | Context layers L1–L4, context packs, reading lists, MODULE.md charters |
| [PromptEngineeringStandards.md](PromptEngineeringStandards.md) | How engineering prompts/task specs are written; worked example |
| [AIValidationWorkflow.md](AIValidationWorkflow.md) | L0–L4 validation ladder, CI-is-truth, hallucination controls, regression prevention |

### Repository governance
| Document | Purpose |
|---|---|
| [RepositoryRules.md](RepositoryRules.md) | Binding monorepo rules: protection, write-sets, never-commit list, CODEOWNERS |
| [RepositoryStructure.md](RepositoryStructure.md) | Target tree, per-path purpose/phase/owner, Gradle module mapping |
| [BranchingStrategy.md](BranchingStrategy.md) | Trunk-based flow, branch naming, PR size, release branches, hotfixes |
| [ModuleOwnership.md](ModuleOwnership.md) | Module → owning role map, CODEOWNERS content, transfer protocol |
| [DependencyManagement.md](DependencyManagement.md) | Dependency approval, licenses, air-gap fitness, update cadence, supply chain |

### Standards
| Document | Purpose |
|---|---|
| [CodingStandards.md](CodingStandards.md) | Java/SQL/TypeScript/test/logging standards, enforced in G1 |
| [ArchitecturePrinciples.md](ArchitecturePrinciples.md) | Standing principles with rationale and enforcement points |
| [ADRProcess.md](ADRProcess.md) | When/how architecture decisions are recorded; template; approval flow |
| [DocumentationStandards.md](DocumentationStandards.md) | Doc taxonomy, change→doc update matrix, docs-lint, style rules |

### Quality gates & checklists
| Document | Purpose |
|---|---|
| [QualityGatePolicy.md](QualityGatePolicy.md) | G0–G8 and RG1–RG4 fully specified; change-class matrix; waiver policy |
| [DefinitionOfReady.md](DefinitionOfReady.md) | G0 checklist — what a task must have before work starts |
| [DefinitionOfDone.md](DefinitionOfDone.md) | DoD checklist with evidence requirements; VERIFIED vs DONE |
| [CodeReviewChecklist.md](CodeReviewChecklist.md) | R-CR's operational checklist; verdict scale; independence rules |
| [SecurityChecklist.md](SecurityChecklist.md) | G3/CC-2 items: RLS, permissions, secrets, audit, PII, AI security, RG2 |
| [PerformanceChecklist.md](PerformanceChecklist.md) | G5/CC-3 items: NFR budgets, query evidence, load tests, regression thresholds |
| [TestingChecklist.md](TestingChecklist.md) | Per-PR testing requirements operationalizing TestingStrategy |
| [ObservabilityRequirements.md](ObservabilityRequirements.md) | G6: metrics/traces/logs/dashboards/alerts for every new path |

### Release & standing policies
| Document | Purpose |
|---|---|
| [ReleaseManagement.md](ReleaseManagement.md) | Release trains, RG gates, hotfix/rollback protocol, go/no-go |
| [VersioningStrategy.md](VersioningStrategy.md) | Platform/SPI/API/event/DB versioning matrix |
| [TechnicalDebtPolicy.md](TechnicalDebtPolicy.md) | Register-or-fix, DEBT lifecycle, capacity reservation, aging rules |
| [RefactoringPolicy.md](RefactoringPolicy.md) | Boy-scout scope, structural refactor protocol, behavior-preservation proof |
| [RiskManagementPolicy.md](RiskManagementPolicy.md) | RISK register, scoring, cadence, seeded initial risks |

## Fixed vocabulary

Roles `R-XX` (21, defined in [AIAgentCatalog.md](AIAgentCatalog.md)) · merge gates `G0`–`G8` · release gates `RG1`–`RG4` · change classes `CC-1`–`CC-7` · task states INTAKE→READY→CLAIMED→IN_PROGRESS→IN_REVIEW→MERGED→VERIFIED→DONE (+BLOCKED, PARKED) · artifacts `TASK-NNNN`, `SPRINT-NN`, `ADR-NNN`, `DEBT-NNN`, `RISK-NNN` · review verdicts BLOCKER/MAJOR/MINOR/NIT. These identifiers are law across all documents; changing them requires an ADR.

## Change control for the EOS itself

The EOS is owned by the Chief Architect (R-CA). Changes to any EOS document follow the same rules as code: PR + G7 + G8, and an ADR when a rule materially changes (gates, authority, lifecycle). The EOS versions with the platform (see [VersioningStrategy.md](VersioningStrategy.md)).
