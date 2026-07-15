/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Thread-bound holder for the current request's {@link EipPrincipal}, set by {@link
 * EipPrincipalFilter} and read by {@link PermissionEnforcementInterceptor}. Mirrors {@link
 * com.eip.tenancy.context.TenantContextHolder}'s shape deliberately: same pooled-thread leak
 * hazard, same fail-closed default (nothing bound reads as no permissions, never as
 * all-permissions).
 */
public final class EipPrincipalHolder {

  private static final ThreadLocal<EipPrincipal> CURRENT = new ThreadLocal<>();

  private EipPrincipalHolder() {}

  /**
   * Binds the principal for the current thread.
   *
   * @param principal the resolved principal
   */
  public static void set(EipPrincipal principal) {
    CURRENT.set(principal);
  }

  /**
   * Returns the current thread's principal, if one is bound.
   *
   * @return the current principal, or empty if none is bound
   */
  public static Optional<EipPrincipal> current() {
    @Nullable EipPrincipal principal = CURRENT.get();
    return Optional.ofNullable(principal);
  }

  /** Clears the current thread's principal. MUST be called when the unit of work ends. */
  public static void clear() {
    CURRENT.remove();
  }
}
