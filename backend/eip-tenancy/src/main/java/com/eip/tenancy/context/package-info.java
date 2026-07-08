/**
 * Tenant context and the Row-Level-Security binding mechanism. {@link
 * com.eip.tenancy.context.RlsTenantBinder} sets the transaction-scoped {@code app.tenant_id} GUC
 * that the Postgres RLS policies key on (DatabasePlan §5/§12) — the backstop that makes a missed
 * application-level tenant filter a non-event rather than a cross-tenant breach.
 *
 * <p>The request/consumer filter that populates the context and drives the binder per transaction
 * is introduced with tenant-context propagation (P0-E3-S1); this package provides the primitive.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.context;

import org.jspecify.annotations.NullMarked;
