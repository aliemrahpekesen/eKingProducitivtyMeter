/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * OIDC resource-server wiring + deny-by-default RBAC enforcement (SecurityModel §3/§4, M5 Wave
 * S1a). {@code eip.security.mode} (header|oidc, BackendPlan §1) selects the {@link
 * org.springframework.security.web.SecurityFilterChain} shape: {@code header} preserves today's
 * behavior (permitAll at this layer; the tenant filter + permission interceptor still run — RBAC is
 * really exercised in every mode, per {@link com.eip.app.security.EipPrincipalFilter}); {@code
 * oidc} requires a valid JWT (issuer/signature/expiry) for every {@code /api/v1} path except the
 * explicit whitelist. {@link com.eip.app.security.RequiresPermission} + {@link
 * com.eip.app.security.PermissionEnforcementInterceptor} enforce deny-by-default (FR-122): an
 * unannotated {@code /api/v1} handler is refused, not silently permitted.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.security;

import org.jspecify.annotations.NullMarked;
