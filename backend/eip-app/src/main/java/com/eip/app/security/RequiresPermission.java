/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.tenancy.rbac.Permission;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission(s) a {@code /api/v1} handler method requires (SecurityModel §4;
 * deny-by-default, FR-122). {@link PermissionEnforcementInterceptor} checks these ANY-OF: the
 * caller's principal must hold at least one listed permission. A handler method under {@code
 * /api/v1} with neither this annotation nor {@link PermissionExempt} is refused with 403 (deny-by-
 * default) — there is no implicit "no permission needed" outcome.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

  /**
   * The permission(s) that satisfy this endpoint; any one is sufficient.
   *
   * @return the any-of permission set
   */
  Permission[] value();
}
