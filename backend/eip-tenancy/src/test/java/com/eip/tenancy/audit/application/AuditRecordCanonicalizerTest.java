/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.tenancy.audit.persistence.AuditEventRepository.ChainCandidateRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuditRecordCanonicalizer}: canonicalization determinism (RawPayloadCodec's
 * same guarantee, mirrored) and SHA-256 chain-hash computation. No Spring context, no database — a
 * plain {@link ObjectMapper}.
 */
class AuditRecordCanonicalizerTest {

  private final AuditRecordCanonicalizer canonicalizer =
      new AuditRecordCanonicalizer(new ObjectMapper());

  @Test
  void canonical_bytes_are_independent_of_json_key_order_at_every_depth() {
    UUID tenantId = UUID.randomUUID();
    // Same id (and every other field) for both rows — only the detail's key order differs, so the
    // assertion isolates key-order independence alone (canonicalBytes includes id, so a per-row
    // random id would make the rows differ for a reason unrelated to what this test checks).
    UUID id = UUID.randomUUID();
    ChainCandidateRow rowA =
        row(
            id,
            "{\"category\":\"secrets\",\"actorType\":\"USER\","
                + "\"target\":{\"type\":\"secret\",\"id\":\"abc\"}}");
    ChainCandidateRow rowB =
        row(
            id,
            "{\"target\":{\"id\":\"abc\",\"type\":\"secret\"},"
                + "\"actorType\":\"USER\",\"category\":\"secrets\"}");

    assertThat(canonicalizer.canonicalBytes(tenantId, rowA))
        .isEqualTo(canonicalizer.canonicalBytes(tenantId, rowB));
  }

  @Test
  void canonical_bytes_are_identical_across_repeated_calls_for_the_same_row() {
    UUID tenantId = UUID.randomUUID();
    ChainCandidateRow row = row("{\"category\":\"auth\"}");

    assertThat(canonicalizer.canonicalBytes(tenantId, row))
        .isEqualTo(canonicalizer.canonicalBytes(tenantId, row));
  }

  @Test
  void canonical_bytes_differ_when_content_differs() {
    UUID tenantId = UUID.randomUUID();
    ChainCandidateRow rowA = row("{\"category\":\"secrets\"}");
    ChainCandidateRow rowB = row("{\"category\":\"admin\"}");

    assertThat(canonicalizer.canonicalBytes(tenantId, rowA))
        .isNotEqualTo(canonicalizer.canonicalBytes(tenantId, rowB));
  }

  @Test
  void canonical_bytes_differ_across_tenants_for_an_otherwise_identical_row() {
    ChainCandidateRow row = row("{\"category\":\"auth\"}");

    assertThat(canonicalizer.canonicalBytes(UUID.randomUUID(), row))
        .isNotEqualTo(canonicalizer.canonicalBytes(UUID.randomUUID(), row));
  }

  @Test
  void genesis_hash_is_deterministic_and_tenant_specific() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    assertThat(canonicalizer.genesisHash(tenantA)).isEqualTo(canonicalizer.genesisHash(tenantA));
    assertThat(canonicalizer.genesisHash(tenantA)).isNotEqualTo(canonicalizer.genesisHash(tenantB));
  }

  @Test
  void compute_hash_is_a_sha256_digest_that_chains_on_prev_hash() {
    UUID tenantId = UUID.randomUUID();
    ChainCandidateRow row = row("{\"category\":\"auth\"}");
    byte[] genesis = canonicalizer.genesisHash(tenantId);

    byte[] hash1 = canonicalizer.computeHash(genesis, tenantId, row);
    byte[] hash1Again = canonicalizer.computeHash(genesis, tenantId, row);
    byte[] hash2 = canonicalizer.computeHash(hash1, tenantId, row); // same row, different prevHash

    assertThat(hash1).hasSize(32).isEqualTo(hash1Again).isNotEqualTo(hash2);
  }

  private static ChainCandidateRow row(String detailJson) {
    return row(UUID.randomUUID(), detailJson);
  }

  private static ChainCandidateRow row(UUID id, String detailJson) {
    return new ChainCandidateRow(
        id,
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        "secret.revealed",
        "SUCCESS",
        "trace-abc",
        detailJson);
  }
}
