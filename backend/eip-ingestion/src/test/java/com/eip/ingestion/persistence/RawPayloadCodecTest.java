/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves canonical JSON is key-order-independent and the content hash is a stable function of it.
 */
class RawPayloadCodecTest {

  private final RawPayloadCodec codec = new RawPayloadCodec(new ObjectMapper());

  @Test
  void canonical_json_sorts_keys_regardless_of_insertion_order() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("b", "2");
    a.put("a", "1");
    Map<String, Object> b = new LinkedHashMap<>();
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

  /**
   * DEBT-018 residual (ConnectorFramework §3): a nested-JSON payload round-trips through canonical
   * JSON with its structure intact — the nested map's own keys are sorted too (not just the top
   * level), while the list's element order is preserved (it is significant array content, not
   * incidental map iteration order).
   */
  @Test
  void nested_json_payload_round_trips_with_structure_intact() throws Exception {
    Map<String, Object> nestedOutOfOrder = new LinkedHashMap<>();
    nestedOutOfOrder.put("d", "later");
    nestedOutOfOrder.put("b", List.of(1, 2));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("a", nestedOutOfOrder);
    payload.put("c", "x");

    String json = codec.toCanonicalJson(payload);

    // Deep key sort: "a" < "c" at the top level, "b" < "d" inside the nested object.
    assertThat(json).isEqualTo("{\"a\":{\"b\":[1,2],\"d\":\"later\"},\"c\":\"x\"}");

    JsonNode roundTripped = new ObjectMapper().readTree(json);
    assertThat(roundTripped.path("a").path("b").get(0).asInt()).isEqualTo(1);
    assertThat(roundTripped.path("a").path("b").get(1).asInt()).isEqualTo(2);
    assertThat(roundTripped.path("a").path("d").asText()).isEqualTo("later");
    assertThat(roundTripped.path("c").asText()).isEqualTo("x");
  }

  /**
   * Regression: a flat producer's canonical JSON — the shape every connector still emits via {@link
   * RawRecord#ofFlat} — is byte-for-byte identical to what the codec produced before the payload
   * type widened from {@code Map<String, String>} to {@code Map<String, Object>}. Widening the type
   * must not introduce any wrapper, type tag, or reordering for the flat case.
   */
  @Test
  void flat_payload_canonical_json_is_unchanged_by_the_widened_payload_type() {
    RawRecord flat =
        RawRecord.ofFlat(
            "work_item",
            "PLAT-1",
            "jira",
            "prod",
            "jira:10001",
            Op.UPSERT,
            FetchKind.FULL,
            Map.of("key", "PLAT-1", "status", "open"));

    assertThat(codec.toCanonicalJson(flat.payload()))
        .isEqualTo("{\"key\":\"PLAT-1\",\"status\":\"open\"}");
  }
}
