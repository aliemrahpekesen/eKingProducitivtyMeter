/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Tenancy persistence adapters (module-internal): {@code JdbcClient} over the control-plane tables.
 * Tenant rows are platform-scoped ({@code core.tenant} has no RLS by design); everything else runs
 * on the caller's tenant-bound transaction.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.persistence;

import org.jspecify.annotations.NullMarked;
