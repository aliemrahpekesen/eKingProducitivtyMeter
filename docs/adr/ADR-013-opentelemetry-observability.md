# ADR-013: Adopt OpenTelemetry-first observability (OTel SDK → Collector → Prometheus/Grafana)

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [ObservabilityModel](../architecture/ObservabilityModel.md) (metric catalog, tracing, dashboards); all modules; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Self-observability must be vendor-neutral and work in air-gapped installs (D6), and traces must follow a unit of work end to end across the asynchronous pipeline.

## Decision

Instrument with the OpenTelemetry SDK exporting to an OTel Collector, then Prometheus and Grafana (Tempo/Loki optional). `traceparent` propagates through the event envelope so a request is traceable across producers and consumers; metric names follow the `eip_*` catalogue.

## Consequences

- **Positive:** vendor-neutral, air-gap-friendly telemetry (D6) using the same stack the product itself integrates; end-to-end traces across the event pipeline via envelope `traceparent`.
- **Negative:** the OTel Collector is an additional component to run; teams must adhere to the `eip_*` naming and propagate `traceparent` on every produced envelope or traces break.

## Alternatives rejected

- **A proprietary or SaaS APM** — rejected: unreachable from air-gapped installs (D6) and a vendor lock-in the on-prem model forbids.
- **Direct Prometheus instrumentation without OpenTelemetry** — rejected: no unified distributed tracing and weaker portability across telemetry backends.
