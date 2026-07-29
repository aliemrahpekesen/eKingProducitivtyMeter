/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

/**
 * Unchecked wrapper: a connector sync failed against the upstream source (unreachable, auth
 * rejected, or non-2xx).
 */
public final class SourceSyncException extends RuntimeException {

  /**
   * Creates a source sync failure.
   *
   * @param message what failed
   * @param cause the underlying cause
   */
  public SourceSyncException(String message, Throwable cause) {
    super(message, cause);
  }
}
