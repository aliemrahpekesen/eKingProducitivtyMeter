/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

/**
 * Outcome of {@link Connector#healthCheck(ConnectorConfig)} — the probe the Connector Health
 * Monitor exposes at {@code GET /api/v1/connectors/{instanceId}/health} (ConnectorFramework §9;
 * DEBT-018 item 2). Carries the identical 3-outcome shape as {@link TestConnectionOutcome}.
 *
 * <p><b>v0.1 equivalence (documented, not accidental):</b> {@link Connector}'s default {@code
 * healthCheck} delegates straight to {@link Connector#testConnection(ConnectorConfig)}. The full
 * ConnectorFramework lifecycle (§3) distinguishes a liveness probe (health, run periodically
 * against an ACTIVE/DEGRADED instance by a scheduler) from a connectivity probe (test, run once
 * before activation) — but this release has no scheduler, no session, and no
 * ACTIVE/DEGRADED/circuit-breaker state machine (DEBT-018 residual) to make that distinction
 * meaningful: both operations are, today, "make one bounded authenticated call and report the
 * outcome honestly." This equivalence is a v0.1 scope statement, not a claim that health and test
 * connection will always coincide once the scheduler/session model lands.
 *
 * @param outcome {@code OK} | {@code NOT_AVAILABLE} | {@code FAILED}
 * @param message operator-facing detail (never secret material)
 */
public record HealthStatus(String outcome, String message) {

  /**
   * Success factory.
   *
   * @param message detail
   * @return an OK outcome
   */
  public static HealthStatus ok(String message) {
    return new HealthStatus("OK", message);
  }

  /**
   * Capability-not-shipped factory.
   *
   * @param message detail
   * @return a NOT_AVAILABLE outcome
   */
  public static HealthStatus notAvailable(String message) {
    return new HealthStatus("NOT_AVAILABLE", message);
  }

  /**
   * Failure factory.
   *
   * @param message operator-actionable detail
   * @return a FAILED outcome
   */
  public static HealthStatus failed(String message) {
    return new HealthStatus("FAILED", message);
  }

  /**
   * Adapts a {@link TestConnectionOutcome} to this shape — the v0.1 equivalence's implementation
   * detail (class javadoc).
   *
   * @param outcome the connectivity-probe outcome
   * @return the equivalent health status
   */
  static HealthStatus from(TestConnectionOutcome outcome) {
    return new HealthStatus(outcome.outcome(), outcome.message());
  }
}
