/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Analytics application services (module-internal): the use-case/query implementations behind
 * {@code com.eip.analytics.api}. Loading and persistence go through the module's repositories; the
 * friction math itself stays in the pure {@code friction} engine. Every unit of work runs in one
 * tenant-bound Spring transaction ({@code TenantTransactionRunner}).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.analytics.application;

import org.jspecify.annotations.NullMarked;
