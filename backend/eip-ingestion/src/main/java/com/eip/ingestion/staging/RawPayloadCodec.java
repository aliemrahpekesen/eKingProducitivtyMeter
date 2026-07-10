/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a raw payload map into the canonical JSON stored in the {@code jsonb} staging column and
 * its SHA-256 content hash. Keys are sorted ({@link TreeMap}) so the JSON — and therefore the hash
 * — is a stable function of the content alone, independent of the map's iteration order. That
 * stability is what makes replay idempotency reliable: the same source record always hashes the
 * same, so an unchanged payload is detected and skipped.
 */
public final class RawPayloadCodec {

  private final ObjectMapper mapper;

  /**
   * Creates a codec over the given mapper.
   *
   * @param mapper the JSON mapper (the app's shared {@link ObjectMapper})
   */
  public RawPayloadCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Serializes the payload to canonical (key-sorted) JSON.
   *
   * @param payload the raw payload map
   * @return the canonical JSON text
   */
  public String toCanonicalJson(Map<String, String> payload) {
    try {
      return mapper.writeValueAsString(new TreeMap<>(payload));
    } catch (JsonProcessingException e) {
      throw new IngestionException("failed to serialize raw payload", e);
    }
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
