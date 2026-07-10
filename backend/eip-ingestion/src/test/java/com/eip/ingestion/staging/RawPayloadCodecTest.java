/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves canonical JSON is key-order-independent and the content hash is a stable function of it.
 */
class RawPayloadCodecTest {

  private final RawPayloadCodec codec = new RawPayloadCodec(new ObjectMapper());

  @Test
  void canonical_json_sorts_keys_regardless_of_insertion_order() {
    Map<String, String> a = new LinkedHashMap<>();
    a.put("b", "2");
    a.put("a", "1");
    Map<String, String> b = new LinkedHashMap<>();
    b.put("a", "1");
    b.put("b", "2");

    assertThat(codec.toCanonicalJson(a)).isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
    assertThat(codec.toCanonicalJson(a)).isEqualTo(codec.toCanonicalJson(b));
  }

  @Test
  void content_hash_is_stable_for_equal_content_and_changes_with_content() {
    String json = codec.toCanonicalJson(Map.of("key", "PLAT-101"));
    assertThat(codec.contentHash(json)).hasSize(32).isEqualTo(codec.contentHash(json));
    assertThat(codec.contentHash(json))
        .isNotEqualTo(codec.contentHash(codec.toCanonicalJson(Map.of("key", "PLAT-102"))));
  }
}
