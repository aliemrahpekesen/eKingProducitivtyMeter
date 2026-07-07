/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.error;

/**
 * The caller is authenticated but lacks the permission required for the action (CodingStandards
 * §2.4; deny-by-default RBAC). Maps to an RFC 7807 {@code 403}-class problem.
 */
public final class PermissionDeniedException extends EipException {

  /**
   * @param message the authorization-failure detail
   */
  public PermissionDeniedException(String message) {
    super(message);
  }

  /**
   * @param message the authorization-failure detail
   * @param cause the underlying cause
   */
  public PermissionDeniedException(String message, Throwable cause) {
    super(message, cause);
  }
}
