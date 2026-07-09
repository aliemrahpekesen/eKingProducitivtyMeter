/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

import com.eip.tenancy.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/demo tenant resolver: reads the tenant id from the {@code X-EIP-Tenant} header. This is a
 * deliberate placeholder — it carries no authentication and MUST NOT be trusted in production. It
 * is replaced by the OIDC token→tenant resolver in SPRINT-02 (P0-E3-S3); the {@link
 * TenantContextFilter} and the RLS binding it drives are the permanent mechanism, so the swap is a
 * one-line bean change.
 *
 * <p>{@code @Profile("!prod")} is a structural guard: this unauthenticated resolver can never be
 * the active {@link TenantResolver} under the {@code prod} profile, so it cannot silently become
 * the production mechanism before the OIDC resolver replaces it.
 */
@Component
@Profile("!prod")
public class HeaderTenantResolver implements TenantResolver {

  /** Request header carrying the tenant id (dev/demo only). */
  public static final String HEADER = "X-EIP-Tenant";

  @Override
  public Optional<TenantContext> resolve(HttpServletRequest request) {
    @Nullable String value = request.getHeader(HEADER);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(TenantContext.of(UUID.fromString(value.trim())));
    } catch (IllegalArgumentException notAUuid) {
      return Optional.empty();
    }
  }
}
