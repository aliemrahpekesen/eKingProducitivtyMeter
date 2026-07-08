/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.context;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Thread-bound holder for the current request's {@link TenantContext}. The web tenant-context
 * filter (and, later, Kafka consumers) set it at the start of a unit of work and clear it at the
 * end; the data layer reads it to bind the RLS GUC via {@link RlsTenantBinder}. Kept as a plain
 * {@code ThreadLocal} so {@code eip-tenancy} stays free of any web/Spring runtime dependency.
 *
 * <p>Threads are pooled, so callers MUST {@link #clear()} in a {@code finally} block to avoid
 * leaking one request's tenant into the next.
 */
public final class TenantContextHolder {

  private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

  private TenantContextHolder() {}

  /**
   * Binds the tenant for the current thread.
   *
   * @param context the tenant context
   */
  public static void set(TenantContext context) {
    CURRENT.set(context);
  }

  /**
   * Returns the current thread's tenant, if one is bound.
   *
   * @return the current tenant context, or empty if none is bound
   */
  public static Optional<TenantContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /**
   * Returns the current thread's tenant, failing closed if none is bound.
   *
   * @return the current tenant context
   * @throws NoTenantBoundException if no tenant is bound (fail-closed — never a silent default)
   */
  public static TenantContext require() {
    @Nullable TenantContext context = CURRENT.get();
    if (context == null) {
      throw new NoTenantBoundException();
    }
    return context;
  }

  /** Clears the current thread's tenant. MUST be called when the unit of work ends. */
  public static void clear() {
    CURRENT.remove();
  }

  /** Thrown when tenant-scoped work is attempted with no tenant bound. */
  public static final class NoTenantBoundException extends RuntimeException {
    NoTenantBoundException() {
      super("no tenant bound to the current context");
    }
  }
}
