/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Tenancy application services (module-internal): admin use-case implementations behind {@code
 * com.eip.tenancy.api}. Tenant operations are platform-scoped; structure operations run inside a
 * tenant-bound transaction via {@code TenantTransactionRunner}.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.application;

import org.jspecify.annotations.NullMarked;
