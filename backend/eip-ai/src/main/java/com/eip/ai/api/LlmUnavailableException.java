/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

/**
 * Unchecked: the configured LLM provider was unreachable, timed out, or returned a non-2xx response
 * (mirrors {@code com.eip.connectors.http.SourceHttp.SourceHttpException}'s style — never carries
 * secret material). Not part of the sealed {@code com.eip.core.error} taxonomy; {@code eip-app}'s
 * {@code ApiExceptionHandler} maps this to a 502 {@code /problems/ai-upstream}.
 */
public final class LlmUnavailableException extends RuntimeException {

  /**
   * Creates the failure.
   *
   * @param message what failed
   */
  public LlmUnavailableException(String message) {
    super(message);
  }

  /**
   * Creates the failure with an underlying cause.
   *
   * @param message what failed
   * @param cause the underlying cause
   */
  public LlmUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
