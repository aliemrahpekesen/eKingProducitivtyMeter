/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ServiceTokenGenerator}'s two contracts (SecurityModel §3, ADR-025): generated raw
 * tokens carry the documented shape and sufficient entropy to never collide in practice, and the
 * hash function is deterministic and matches an independently-computed SHA-256 test vector.
 */
class ServiceTokenGeneratorTest {

  private final ServiceTokenGenerator generator = new ServiceTokenGenerator();

  @Test
  void generatedTokensCarryTheDocumentedPrefixAndLength() {
    String token = generator.generateRawToken();

    assertThat(token).startsWith("eipt_");
    // "eipt_" + 32 URL-safe base64 characters (SecurityModel §3).
    assertThat(token).hasSize(5 + 32);
    assertThat(token.substring(5)).matches("[A-Za-z0-9_-]{32}");
  }

  @Test
  void tenThousandGeneratedTokensAreAllUnique() {
    Set<String> tokens =
        IntStream.range(0, 10_000)
            .mapToObj(i -> generator.generateRawToken())
            .collect(Collectors.toCollection(HashSet::new));

    assertThat(tokens).hasSize(10_000);
  }

  @Test
  void hashIsDeterministic() {
    String token = generator.generateRawToken();

    assertThat(generator.hash(token)).isEqualTo(generator.hash(token));
  }

  @Test
  void hashMatchesAKnownSha256TestVector() {
    // Independently computed: python3 -c "import hashlib;
    // print(hashlib.sha256(b'eipt_test-vector').hexdigest())"
    String knownInput = "eipt_test-vector";
    String expectedSha256Hex = "6e8de50ef2fbc8b936e908817425fbc5c22b66cafd555ed4600ce55b59eaeb9a";

    assertThat(generator.hash(knownInput)).isEqualTo(expectedSha256Hex);
  }
}
