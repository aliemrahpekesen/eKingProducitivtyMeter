/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Reports persistence adapters (module-internal). {@code JdbcClient} over {@code
 * reports.generated_report} (TASK-0022, ADR-023): insert, keyset-paginated list, and by-id read.
 * Every statement runs on the tenant-bound transaction opened by {@code TenantTransactionRunner}.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.reports.persistence;

import org.jspecify.annotations.NullMarked;
