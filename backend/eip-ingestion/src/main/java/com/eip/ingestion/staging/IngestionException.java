/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

import org.jspecify.annotations.Nullable;

/** Unchecked wrapper for a failure while staging or ingesting raw records. */
public final class IngestionException extends RuntimeException {

  /**
   * Creates an ingestion failure.
   *
   * @param message what failed
   * @param cause the underlying cause, or {@code null} if there is none
   */
  public IngestionException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
