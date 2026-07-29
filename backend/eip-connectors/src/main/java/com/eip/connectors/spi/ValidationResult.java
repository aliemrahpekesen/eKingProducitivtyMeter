/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

import java.util.List;

/**
 * Outcome of {@link Connector#validate(ConnectorConfig)} — pure schema/semantic validation, no
 * network round-trip (ConnectorFramework §3: "{@code validate()} is pure schema/semantic validation
 * (no network). {@code testConnection()} performs a bounded, read-only probe."; DEBT-018 item 1).
 * {@code errors} names every missing or blank required field; empty exactly when {@code valid} is
 * {@code true}.
 *
 * @param valid whether the configuration passes validation
 * @param errors operator-facing validation errors (empty when valid)
 */
public record ValidationResult(boolean valid, List<String> errors) {

  public ValidationResult {
    errors = List.copyOf(errors);
  }

  /**
   * Success factory.
   *
   * @return a valid result with no errors
   */
  public static ValidationResult ok() {
    return new ValidationResult(true, List.of());
  }

  /**
   * Failure factory.
   *
   * @param errors the validation errors; must not be empty
   * @return an invalid result carrying the errors
   */
  public static ValidationResult invalid(List<String> errors) {
    return new ValidationResult(false, errors);
  }
}
