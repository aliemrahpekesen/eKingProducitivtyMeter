/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.application;

import com.eip.core.error.InternalException;
import com.eip.tenancy.audit.persistence.AuditEventRepository.ChainCandidateRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Computes the audit hash-chain's per-row hash: {@code hash = SHA-256(prevHash || canonical(row))}
 * (SecurityModel §11). {@code canonical(row)} mirrors {@code
 * com.eip.ingestion.persistence.RawPayloadCodec}'s recursive {@link TreeMap} re-keying — every
 * {@link Map} level, top-level and nested inside {@code detail}, is sorted into a {@link TreeMap}
 * so the canonical JSON (and therefore the hash) is a stable function of content alone, independent
 * of any map's iteration order. {@link List} order is left untouched: it is significant content.
 *
 * <p>The genesis hash is tenant-specific ({@code SHA-256("EIP-AUDIT-GENESIS:" + tenantId)}) rather
 * than one universal constant, so a forged sub-chain from one tenant cannot be spliced onto another
 * tenant's history by reusing the same anchor.
 */
@Component
public class AuditRecordCanonicalizer {

  private static final String GENESIS_PREFIX = "EIP-AUDIT-GENESIS:";

  private final ObjectMapper mapper;

  /**
   * Creates the canonicalizer.
   *
   * @param mapper the shared {@link ObjectMapper}
   */
  public AuditRecordCanonicalizer(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * A tenant's fixed chain-start anchor, used as {@code prevHash} for that tenant's first row.
   *
   * @param tenantId the tenant
   * @return the 32-byte genesis hash
   */
  public byte[] genesisHash(UUID tenantId) {
    return sha256((GENESIS_PREFIX + tenantId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Computes one row's chain hash.
   *
   * @param prevHash the previous row's hash (or this tenant's {@link #genesisHash} for the first
   *     row)
   * @param tenantId the owning tenant (part of the canonical bytes, so a row cannot be replayed
   *     under a different tenant's chain)
   * @param row the row's fields
   * @return {@code SHA-256(prevHash || canonical(row))}
   */
  public byte[] computeHash(byte[] prevHash, UUID tenantId, ChainCandidateRow row) {
    byte[] canonical = canonicalBytes(tenantId, row);
    byte[] combined = new byte[prevHash.length + canonical.length];
    System.arraycopy(prevHash, 0, combined, 0, prevHash.length);
    System.arraycopy(canonical, 0, combined, prevHash.length, canonical.length);
    return sha256(combined);
  }

  /**
   * Serializes a row's hashable fields to canonical (recursively key-sorted) JSON bytes.
   *
   * @param tenantId the owning tenant
   * @param row the row's fields
   * @return the canonical JSON, UTF-8 encoded
   */
  byte[] canonicalBytes(UUID tenantId, ChainCandidateRow row) {
    Map<String, Object> fields = new TreeMap<>();
    fields.put("id", row.id().toString());
    fields.put("tenantId", tenantId.toString());
    fields.put("occurredAt", row.occurredAt().toString());
    fields.put(
        "actorMemberId", row.actorMemberId() == null ? null : row.actorMemberId().toString());
    fields.put("action", row.action());
    fields.put("outcome", row.outcome());
    fields.put("traceId", row.traceId());
    fields.put("detail", canonicalizeJson(row.detailJson()));
    try {
      return mapper.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new InternalException("failed to canonicalize audit row " + row.id(), e);
    }
  }

  /**
   * Parses {@code detail}'s raw jsonb text and re-keys every nested {@link Map} into a sorted one.
   */
  private Object canonicalizeJson(String detailJson) {
    try {
      return canonicalize(mapper.readValue(detailJson, Object.class));
    } catch (JsonProcessingException e) {
      throw new InternalException("failed to parse audit detail for canonicalization", e);
    }
  }

  /**
   * Recursively sorts every nested {@link Map}'s keys into a {@link TreeMap}; {@link List} elements
   * are canonicalized in place (order preserved) and every other value (String, Number, Boolean,
   * null) is returned unchanged. Mirrors {@code RawPayloadCodec.canonicalize} exactly.
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
      return list.stream().map(AuditRecordCanonicalizer::canonicalize).toList();
    }
    return value;
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new InternalException("SHA-256 unavailable", e); // never on a conformant JRE
    }
  }
}
