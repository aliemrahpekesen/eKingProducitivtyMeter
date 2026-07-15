/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Tenancy admin ports (M1 admin panel, ADR-022): platform-level tenant management and tenant-scoped
 * organisation-structure management. Until OIDC/RBAC lands (DEBT-012) these are gated only by
 * environment (prod refuses to boot); the RBAC permission catalog attaches here.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.api;

import org.jspecify.annotations.NullMarked;
