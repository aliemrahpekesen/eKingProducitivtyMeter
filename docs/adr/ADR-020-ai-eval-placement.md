# ADR-020: Run deterministic prompt-contract tests at the merge gate and model-based evals nightly and at release gates

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [TestingStrategy](../testing/TestingStrategy.md) (CI stages, AI evals), [AgentArchitecture](../ai/AgentArchitecture.md); `eip-ai`; CC-1 / AI-behavior (CC-5).

## Context

Recorded by the architecture readiness review, 2026-07-06 ([ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md), forced by AIR-03). AgentArchitecture implied per-merge CI evals against a pinned local model, which contradicts TestingStrategy's nightly placement and the reality that air-gapped CI has no model-serving hardware on the merge path.

## Decision

The CC-5 merge gate runs deterministic prompt-contract tests with no live model. The model-based eval suite runs nightly and at release gates (RG1 for AI-phase releases) on the pinned local model on reference hardware — never on the PR merge path.

## Consequences

- **Positive:** keeps live models off the PR merge path, so merges stay fast, deterministic, and feasible on air-gapped CI, while release gates still verify real-model behaviour before a version ships.
- **Negative:** model-behaviour regressions surface at nightly/release cadence rather than per PR; the nightly suite requires reference hardware and a pinned local model to run.

## Alternatives rejected

- **Run model-based evals on every merge against a pinned local model** — rejected: slow, non-deterministic, and infeasible on air-gapped CI without model-serving hardware in the merge path.
- **Run no model-based evals at all** — rejected: real-model behaviour would go unverified before release, defeating the purpose of the eval suite.
