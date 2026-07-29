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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/demo tenant resolver: reads the tenant id from the {@code X-EIP-Tenant} header. This is a
 * deliberate placeholder — it carries no authentication and MUST NOT be trusted in production. It
 * is replaced by {@link OidcTenantResolver} whenever {@code eip.security.mode=oidc} (M5 Wave S1a,
 * DEBT-012); the {@link TenantContextFilter} and the RLS binding it drives are the permanent
 * mechanism, so the swap is a one-line bean change.
 *
 * <p>Two independent structural guards keep this unauthenticated resolver out of production, so
 * neither one alone is load-bearing: {@code @Profile("!prod")} excludes the {@code prod} profile
 * outright, and {@code @ConditionalOnProperty(..., havingValue = "header")} excludes it whenever
 * {@code eip.security.mode=oidc} — which {@code com.eip.app.config.ProductionTenantResolutionGuard}
 * requires for {@code prod}/{@code preprod} to boot at all.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(
    prefix = "eip.security",
    name = "mode",
    havingValue = "header",
    matchIfMissing = true)
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
