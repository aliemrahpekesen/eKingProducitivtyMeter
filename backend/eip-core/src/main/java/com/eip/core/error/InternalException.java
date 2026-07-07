/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.error;

/**
 * An unexpected internal failure that is not the caller's fault (CodingStandards §2.4). Maps to an
 * RFC 7807 {@code 500}-class problem.
 *
 * <p>Contract: responses derived from this carry only a {@code traceId} and a generic detail —
 * never a stack trace or internal specifics to the client.
 */
public final class InternalException extends EipException {

  /**
   * @param message the internal-failure detail (server-side only; not surfaced verbatim to clients)
   */
  public InternalException(String message) {
    super(message);
  }

  /**
   * @param message the internal-failure detail (server-side only; not surfaced verbatim to clients)
   * @param cause the underlying cause
   */
  public InternalException(String message, Throwable cause) {
    super(message, cause);
  }
}
