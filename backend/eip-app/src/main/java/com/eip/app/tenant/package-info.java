/**
 * Web tenant-context wiring: resolves the request's tenant, binds it for the request thread ({@link
 * com.eip.tenancy.context.TenantContextHolder}), and projects it onto the RLS GUC per transaction
 * ({@link com.eip.tenancy.context.RlsTenantBinder}) so every read is tenant-isolated by the
 * database. The dev {@link com.eip.app.tenant.HeaderTenantResolver} is replaced by {@link
 * com.eip.app.tenant.OidcTenantResolver} whenever {@code eip.security.mode=oidc} (M5 Wave S1a); the
 * filter and RLS binding are the permanent mechanism regardless of which resolver is active.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.tenant;

import org.jspecify.annotations.NullMarked;
