/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Unit-tests {@link LazyJwtDecoder}: the delegate supplier must not run until the first {@link
 * JwtDecoder#decode} call, and must run at most once thereafter.
 */
class LazyJwtDecoderTest {

  @Test
  void does_not_build_the_delegate_until_the_first_decode_call() {
    AtomicInteger builds = new AtomicInteger();
    LazyJwtDecoder decoder =
        new LazyJwtDecoder(
            () -> {
              builds.incrementAndGet();
              return token -> stubJwt();
            });

    assertThat(builds).hasValue(0);

    decoder.decode("any-token");

    assertThat(builds).hasValue(1);
  }

  @Test
  void builds_the_delegate_at_most_once() {
    AtomicInteger builds = new AtomicInteger();
    LazyJwtDecoder decoder =
        new LazyJwtDecoder(
            () -> {
              builds.incrementAndGet();
              return token -> stubJwt();
            });

    decoder.decode("first");
    decoder.decode("second");
    decoder.decode("third");

    assertThat(builds).hasValue(1);
  }

  private static Jwt stubJwt() {
    return Jwt.withTokenValue("token-value")
        .header("alg", "RS256")
        .claim("sub", "test-subject")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}
