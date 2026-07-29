/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.error;

/**
 * A requested resource does not exist or is not visible to the caller's tenant (CodingStandards
 * §2.4). Maps to an RFC 7807 {@code 404}-class problem.
 *
 * <p>Contract: this exception MUST NOT leak cross-tenant existence — a resource in another tenant
 * is reported as not-found, never as forbidden, so tenant boundaries stay invisible.
 */
public final class ResourceNotFoundException extends EipException {

  /**
   * @param message the not-found detail (tenant-safe; never reveals cross-tenant existence)
   */
  public ResourceNotFoundException(String message) {
    super(message);
  }

  /**
   * @param message the not-found detail (tenant-safe; never reveals cross-tenant existence)
   * @param cause the underlying cause
   */
  public ResourceNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
