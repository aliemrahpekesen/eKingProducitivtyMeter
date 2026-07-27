/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import com.eip.ingestion.api.IngestionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Turns a raw payload map into the canonical JSON stored in the {@code jsonb} staging column and
 * its SHA-256 content hash. Every {@link Map} level — top-level and nested (RawRecord's payload may
 * be arbitrary nested JSON, ConnectorFramework §3) — is recursively re-keyed into a {@link TreeMap}
 * so the JSON, and therefore the hash, is a stable function of the content alone at every depth,
 * independent of any level's map iteration order. {@link List} order is left untouched: it is
 * significant JSON array content, not incidental map iteration order. That stability is what makes
 * replay idempotency reliable: the same source record always hashes the same, so an unchanged
 * payload is detected and skipped.
 */
@Component
public class RawPayloadCodec {

  private final ObjectMapper mapper;

  /**
   * Creates a codec over the application's configured JSON mapper.
   *
   * @param mapper the shared {@link ObjectMapper}
   */
  public RawPayloadCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Serializes the payload to canonical (recursively key-sorted) JSON.
   *
   * @param payload the raw payload map
   * @return the canonical JSON text
   */
  public String toCanonicalJson(Map<String, Object> payload) {
    try {
      return mapper.writeValueAsString(canonicalize(payload));
    } catch (JsonProcessingException e) {
      throw new IngestionException("failed to serialize raw payload", e);
    }
  }

  /**
   * Recursively sorts every nested {@link Map}'s keys into a {@link TreeMap}; {@link List} elements
   * are canonicalized in place (order preserved) and every other value (String, Number, Boolean,
   * null) is returned unchanged.
   */
  private static Object canonicalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        sorted.put((String) entry.getKey(), canonicalize(entry.getValue()));
      }
      return sorted;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(RawPayloadCodec::canonicalize).toList();
    }
    return value;
  }

  /**
   * Computes the SHA-256 content hash of canonical JSON.
   *
   * @param canonicalJson the canonical JSON text
   * @return the 32-byte SHA-256 digest
   */
  public byte[] contentHash(String canonicalJson) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IngestionException("SHA-256 unavailable", e); // never on a conformant JRE
    }
  }
}
