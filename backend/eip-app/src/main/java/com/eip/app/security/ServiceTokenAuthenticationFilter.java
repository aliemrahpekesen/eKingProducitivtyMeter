/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.app.persistence.ServiceTokenRepository;
import com.eip.app.persistence.ServiceTokenRepository.AuthRecord;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import com.eip.tenancy.rbac.ServiceTokenGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-token authentication (SecurityModel §3): a third, mode-independent path alongside {@code
 * header}/{@code oidc} for CI/scripts. If the {@code Authorization} bearer value starts with {@link
 * ServiceTokenGenerator#TOKEN_PREFIX}, this filter hashes it, looks it up via {@link
 * ServiceTokenRepository#findByHash}, and — on success — sets a {@link ServiceTokenAuthentication}
 * in the security context (the same seam {@code oidc} mode's {@code JwtAuthenticationToken}
 * populates; {@link EipPrincipalFilter} and {@link com.eip.app.tenant.TenantContextFilter} both
 * check for it first, before falling back to their mode-specific resolution). Any other {@code
 * Authorization} value (missing, not {@code eipt_}-prefixed, or a real JWT) passes through
 * unchanged — service tokens are additive, never replacing header/OIDC auth.
 *
 * <p>Registered via {@code SecurityConfig#addFilterBefore(..., BearerTokenAuthenticationFilter)} —
 * unconditionally, in both {@code eip.security.mode}s — so it runs before {@code oidc} mode's
 * OAuth2 resource-server filter would otherwise try (and fail) to parse an {@code eipt_...} value
 * as a JWT. Once a service token authenticates successfully, the {@code Authorization} header is
 * masked from every downstream filter/handler (see {@link #maskAuthorization}) for the same reason:
 * the resource-server filter does not check for a pre-existing {@link
 * org.springframework.security.core.Authentication} before attempting its own parse, so leaving the
 * header visible would let it try anyway and fail the request with an invalid-JWT 401.
 */
@Component
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final Logger log = LoggerFactory.getLogger("com.eip.app.security.serviceToken");

  private final ServiceTokenRepository repository;
  private final ServiceTokenGenerator tokenGenerator;
  private final ObjectMapper mapper;
  private final Tracer tracer;

  /**
   * Creates the filter.
   *
   * @param repository the service-token lookup/touch adapter
   * @param tokenGenerator supplies {@link ServiceTokenGenerator#hash}
   * @param mapper the shared Jackson mapper (rejected-token problem+json body)
   * @param tracer the active tracer (rejected-token problem+json {@code traceId})
   */
  public ServiceTokenAuthenticationFilter(
      ServiceTokenRepository repository,
      ServiceTokenGenerator tokenGenerator,
      ObjectMapper mapper,
      Tracer tracer) {
    this.repository = repository;
    this.tokenGenerator = tokenGenerator;
    this.mapper = mapper;
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<String> rawToken = extractServiceToken(request);
    if (rawToken.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    String hash = tokenGenerator.hash(rawToken.get());
    Optional<AuthRecord> found = repository.findByHash(hash);
    if (found.isEmpty()) {
      reject(response);
      return;
    }
    AuthRecord record = found.get();
    EipPrincipal principal = toPrincipal(record);
    SecurityContextHolder.getContext()
        .setAuthentication(new ServiceTokenAuthentication(record.id(), principal));
    logEvent("auth.token.used", record.id(), record.tenantId());
    touchLastUsedBestEffort(record.id());
    chain.doFilter(maskAuthorization(request), response);
  }

  private void reject(HttpServletResponse response) throws IOException {
    log.warn("event=auth.token.rejected reason=unknown_expired_or_revoked");
    ProblemResponses.write(
        response,
        mapper,
        tracer,
        HttpStatus.UNAUTHORIZED,
        "/problems/unauthenticated",
        "Unauthenticated",
        "service token expired, revoked, or unknown");
  }

  private void touchLastUsedBestEffort(UUID tokenId) {
    try {
      repository.touchLastUsed(tokenId);
    } catch (RuntimeException e) {
      // Best-effort per SecurityModel §3: a last-used tracking failure must never break request
      // authentication, which has already succeeded by this point.
      log.warn("failed to record service token last-used timestamp (tokenId={})", tokenId, e);
    }
  }

  private static EipPrincipal toPrincipal(AuthRecord record) {
    Role role = Role.valueOf(record.role());
    List<String> subset = record.permissionSubset();
    Set<Permission> permissions =
        subset.isEmpty()
            ? role.permissions()
            : subset.stream()
                .map(Permission::valueOf)
                .collect(
                    java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(Permission.class)));
    return new EipPrincipal(
        record.tenantId(), Set.of(role), permissions, "service-token:" + record.id());
  }

  private static Optional<String> extractServiceToken(HttpServletRequest request) {
    @Nullable String header = request.getHeader(AUTHORIZATION_HEADER);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    String value = header.substring(BEARER_PREFIX.length()).trim();
    return value.startsWith(ServiceTokenGenerator.TOKEN_PREFIX)
        ? Optional.of(value)
        : Optional.empty();
  }

  private static void logEvent(String event, UUID tokenId, @Nullable UUID tenantId) {
    MDC.put("event", event);
    MDC.put("tokenId", tokenId.toString());
    if (tenantId != null) {
      MDC.put("scopeTenantId", tenantId.toString());
    }
    try {
      log.info("service token authenticated");
    } finally {
      MDC.remove("event");
      MDC.remove("tokenId");
      MDC.remove("scopeTenantId");
    }
  }

  /**
   * Wraps the request so every downstream filter/handler sees no {@code Authorization} header —
   * once a service token has authenticated the request, the OAuth2 resource-server filter (in
   * {@code oidc} mode) must not also try to parse the same value as a JWT (see class javadoc).
   */
  private static HttpServletRequest maskAuthorization(HttpServletRequest request) {
    return new HttpServletRequestWrapper(request) {
      @Override
      public @Nullable String getHeader(String name) {
        return AUTHORIZATION_HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
      }

      @Override
      public Enumeration<String> getHeaders(String name) {
        return AUTHORIZATION_HEADER.equalsIgnoreCase(name)
            ? Collections.emptyEnumeration()
            : super.getHeaders(name);
      }

      @Override
      public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(
            Collections.list(super.getHeaderNames()).stream()
                .filter(n -> !AUTHORIZATION_HEADER.equalsIgnoreCase(n))
                .toList());
      }
    };
  }
}
