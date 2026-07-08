/**
 * Web tenant-context wiring: resolves the request's tenant, binds it for the request thread ({@link
 * com.eip.tenancy.context.TenantContextHolder}), and projects it onto the RLS GUC per transaction
 * ({@link com.eip.tenancy.context.RlsTenantBinder}) so every read is tenant-isolated by the
 * database. The dev {@code HeaderTenantResolver} is replaced by the OIDC token→tenant resolver in
 * SPRINT-02; the filter and RLS binding are the permanent mechanism.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.tenant;

import org.jspecify.annotations.NullMarked;
