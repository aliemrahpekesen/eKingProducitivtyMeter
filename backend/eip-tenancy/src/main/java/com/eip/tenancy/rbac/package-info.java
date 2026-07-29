/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The RBAC permission catalog (SecurityModel §4): the authoritative {@link
 * com.eip.tenancy.rbac.Permission} enum (wire ids) and the {@link com.eip.tenancy.rbac.Role} →
 * permission-set matrix every {@code /api/v1} endpoint's {@code @RequiresPermission} declares
 * against. Deliberately pure (no Spring/web dependency) so the matrix is unit-testable as plain
 * Java and referenced from any module — or a future service-token/claim-mapping layer — without
 * pulling in a web runtime.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.rbac;

import org.jspecify.annotations.NullMarked;
