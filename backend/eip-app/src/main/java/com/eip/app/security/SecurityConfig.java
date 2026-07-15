/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The {@code /api/v1} security filter chain (SecurityModel §3): stateless + CSRF-off in both modes.
 * {@code header} mode preserves today's behavior at this layer (permitAll — the tenant filter and
 * {@link PermissionEnforcementInterceptor} still run, so RBAC is genuinely exercised). {@code oidc}
 * mode requires a valid JWT (issuer/signature/expiry, plus the {@code eip_tenant} claim via {@link
 * EipTenantClaimValidator}) for everything except the explicit whitelist.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /** Paths that never require authentication, in either mode (own auth, or pre-login). */
  private static final String[] PERMIT_ALL_PATTERNS = {
    "/actuator/**", "/v3/api-docs/**", "/api/v1/webhooks/**", "/error"
  };

  private final EipSecurityProperties properties;
  private final ProblemAuthenticationEntryPoint authenticationEntryPoint;
  private final ProblemAccessDeniedHandler accessDeniedHandler;

  /**
   * Creates the security configuration.
   *
   * @param properties the active {@code eip.security.mode}
   * @param authenticationEntryPoint the 401 problem+json renderer
   * @param accessDeniedHandler the 403 problem+json renderer
   */
  public SecurityConfig(
      EipSecurityProperties properties,
      ProblemAuthenticationEntryPoint authenticationEntryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler) {
    this.properties = properties;
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.accessDeniedHandler = accessDeniedHandler;
  }

  /**
   * Builds the single {@code /api/v1} filter chain, shaped by {@code eip.security.mode}.
   *
   * @param http the HTTP security builder
   * @param jwtDecoders supplies the {@code oidc}-mode {@link JwtDecoder} bean; absent in {@code
   *     header} mode, where it is never dereferenced
   * @return the built filter chain
   * @throws Exception if the builder fails to configure (Spring Security's checked contract)
   */
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, ObjectProvider<JwtDecoder> jwtDecoders) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(PERMIT_ALL_PATTERNS).permitAll();
              auth.requestMatchers(HttpMethod.GET, "/api/v1/session/auth").permitAll();
              if (properties.oidc()) {
                auth.anyRequest().authenticated();
              } else {
                auth.anyRequest().permitAll();
              }
            });
    if (properties.oidc()) {
      http.oauth2ResourceServer(
          oauth2 ->
              oauth2
                  .jwt(jwt -> jwt.decoder(jwtDecoders.getObject()))
                  .authenticationEntryPoint(authenticationEntryPoint));
    }
    return http.build();
  }

  /**
   * The {@code oidc}-mode {@link JwtDecoder}: standard issuer/signature/expiry validation plus
   * {@link EipTenantClaimValidator}, so a token missing {@code eip_tenant} never authenticates.
   * Conditional on the mode so {@code header} deployments never attempt OIDC discovery against the
   * configured (possibly unreachable, e.g. dev-without-Keycloak) issuer. Wrapped in {@link
   * LazyJwtDecoder}: issuer discovery is a synchronous network call, deferred here until a real
   * token needs decoding — so the bean itself can be created (and {@code oidc}-mode tests using
   * {@code SecurityMockMvcRequestPostProcessors.jwt()}, which never call {@link JwtDecoder#decode},
   * can run) with no reachable IdP at all.
   *
   * @param issuerUri the OIDC issuer (SecurityModel §3; {@code EIP_OIDC_ISSUER})
   * @return the validating decoder
   */
  @Bean
  @ConditionalOnProperty(prefix = "eip.security", name = "mode", havingValue = "oidc")
  public JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
    return new LazyJwtDecoder(() -> buildDecoder(issuerUri));
  }

  private static JwtDecoder buildDecoder(String issuerUri) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
    OAuth2TokenValidator<Jwt> validators =
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuerUri), new EipTenantClaimValidator());
    decoder.setJwtValidator(validators);
    return decoder;
  }
}
