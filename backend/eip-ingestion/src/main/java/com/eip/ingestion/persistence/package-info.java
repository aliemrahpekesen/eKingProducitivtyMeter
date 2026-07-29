/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Ingestion persistence adapters (module-internal). {@code JdbcClient} for reads, {@code
 * JdbcTemplate} batch upserts (PostgreSQL {@code INSERT ... ON CONFLICT}) for the high-volume
 * staging and canonical writes (BackendPlan §5). Every statement runs on the tenant-bound
 * transaction opened by the application layer's {@code TenantTransactionRunner}; every INSERT keys
 * {@code tenant_id} off {@code current_setting('app.tenant_id')} so RLS WITH CHECK holds.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.ingestion.persistence;

import org.jspecify.annotations.NullMarked;
