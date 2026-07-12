/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * eip-app infrastructure adapters for the control-plane read surface (session, connector registry).
 * {@code JdbcClient} reads inside tenant-bound read-only transactions ({@code
 * TenantTransactionRunner}); cursor pagination mechanics live here, never in controllers.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.persistence;

import org.jspecify.annotations.NullMarked;
