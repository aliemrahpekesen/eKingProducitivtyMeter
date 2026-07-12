/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The tenant-aware transaction boundary (BackendPlan §2.4/§6, DatabasePlan §12). {@link
 * com.eip.tenancy.tx.TenantTransactionRunner} is the ONE place that opens a Spring-managed local
 * transaction and binds the {@code app.tenant_id} RLS GUC onto that transaction's connection —
 * application services and repositories never touch {@code java.sql.Connection} or manage
 * commit/rollback themselves. The low-level {@code SET LOCAL} mechanics stay in {@link
 * com.eip.tenancy.context.RlsTenantBinder}.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.tx;

import org.jspecify.annotations.NullMarked;
