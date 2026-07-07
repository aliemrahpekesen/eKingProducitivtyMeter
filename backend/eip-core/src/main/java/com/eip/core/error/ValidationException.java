/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.error;

/**
 * A request or value failed validation of its shape or invariants (CodingStandards §2.4). Maps to
 * an RFC 7807 {@code 400}-class problem at the API boundary.
 */
public final class ValidationException extends EipException {

  /**
   * @param message the validation failure detail
   */
  public ValidationException(String message) {
    super(message);
  }

  /**
   * @param message the validation failure detail
   * @param cause the underlying cause
   */
  public ValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
