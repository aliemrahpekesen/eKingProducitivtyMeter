/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Defers building the real {@link JwtDecoder} (issuer discovery — a synchronous network call
 * against the configured OIDC issuer) until the first token actually needs decoding, mirroring the
 * laziness Spring Boot's own resource-server auto-configuration applies for exactly this reason: an
 * {@code oidc}-mode app must still boot (and, crucially, its test suite must still run with no real
 * IdP present — {@code OidcRbacIntegrationTest} authenticates every request via {@code
 * SecurityMockMvcRequestPostProcessors.jwt()}, which never calls {@link #decode}) even when the
 * issuer is temporarily or permanently unreachable.
 */
final class LazyJwtDecoder implements JwtDecoder {

  private final Supplier<JwtDecoder> delegateSupplier;
  private volatile @Nullable JwtDecoder delegate;

  LazyJwtDecoder(Supplier<JwtDecoder> delegateSupplier) {
    this.delegateSupplier = delegateSupplier;
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    return delegate().decode(token);
  }

  private JwtDecoder delegate() {
    JwtDecoder current = delegate;
    if (current == null) {
      synchronized (this) {
        current = delegate;
        if (current == null) {
          current = delegateSupplier.get();
          delegate = current;
        }
      }
    }
    return current;
  }
}
