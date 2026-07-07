/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.error;

/**
 * Sealed root of the EIP exception taxonomy (CodingStandards §2.4; BackendPlan §10). Every
 * exception that crosses a module API boundary MUST extend this root through one of its permitted
 * leaves, so the RFC 7807 problem+json translation in {@code eip-app} switches over a single closed
 * hierarchy.
 *
 * <p>Unchecked by design — checked-exception tunneling is forbidden (CodingStandards §2.1). Sealing
 * is confined to the tenancy-agnostic kernel leaves; module-specific leaves are addressed by a
 * future ADR (TASK-0005 approach, condition C1).
 */
public abstract sealed class EipException extends RuntimeException
    permits ValidationException,
        ResourceNotFoundException,
        PermissionDeniedException,
        InternalException {

  /**
   * Creates an exception with a human-readable message.
   *
   * @param message the detail message
   */
  protected EipException(String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and an underlying cause.
   *
   * @param message the detail message
   * @param cause the underlying cause
   */
  protected EipException(String message, Throwable cause) {
    super(message, cause);
  }
}
