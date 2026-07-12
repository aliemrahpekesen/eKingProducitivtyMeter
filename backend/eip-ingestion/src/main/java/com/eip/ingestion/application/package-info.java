/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Ingestion application services (module-internal): the use-case implementations behind {@code
 * com.eip.ingestion.api}. Each use case runs inside one tenant-bound Spring transaction ({@code
 * TenantTransactionRunner}); connector fetches happen outside the transaction so no source-system
 * call ever holds a database transaction open (BackendPlan §6).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.ingestion.application;

import org.jspecify.annotations.NullMarked;
