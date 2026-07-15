/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

/**
 * Outcome of a connector's connectivity probe. Implementations must be HONEST: {@code OK} only
 * after a real authenticated round-trip; {@code NOT_AVAILABLE} when the capability is not
 * implemented in this release; {@code FAILED} with an operator-actionable message otherwise.
 *
 * @param outcome {@code OK} | {@code NOT_AVAILABLE} | {@code FAILED}
 * @param message operator-facing detail (never secret material)
 */
public record TestConnectionOutcome(String outcome, String message) {

  /**
   * Success factory.
   *
   * @param message detail
   * @return an OK outcome
   */
  public static TestConnectionOutcome ok(String message) {
    return new TestConnectionOutcome("OK", message);
  }

  /**
   * Capability-not-shipped factory.
   *
   * @param message detail
   * @return a NOT_AVAILABLE outcome
   */
  public static TestConnectionOutcome notAvailable(String message) {
    return new TestConnectionOutcome("NOT_AVAILABLE", message);
  }

  /**
   * Failure factory.
   *
   * @param message operator-actionable detail
   * @return a FAILED outcome
   */
  public static TestConnectionOutcome failed(String message) {
    return new TestConnectionOutcome("FAILED", message);
  }
}
