/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import com.eip.connectors.spi.RawRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Raw-staging adapter for {@code staging.raw_simulation}. Set-based by design: one SELECT loads the
 * existing content hashes for a connector, and one {@code JdbcTemplate} batch runs a single
 * PostgreSQL {@code INSERT ... ON CONFLICT DO UPDATE} for every changed record — statement count is
 * independent of record count. The {@code WHERE ... IS DISTINCT FROM} guard makes the upsert a
 * database-level no-op for unchanged payloads (no {@code ingested_at} churn), even though callers
 * already pre-classify.
 */
@Repository
public class StagingRawRepository {

  /** Whitelisted raw tables by connector type — SQL is composed ONLY from these constants. */
  private static final java.util.Map<String, String> RAW_TABLES =
      java.util.Map.ofEntries(
          java.util.Map.entry("simulation", "staging.raw_simulation"),
          java.util.Map.entry("jira", "staging.raw_jira"),
          java.util.Map.entry("bitbucket", "staging.raw_bitbucket"),
          java.util.Map.entry("sonarqube", "staging.raw_sonarqube"),
          java.util.Map.entry("github", "staging.raw_github"),
          java.util.Map.entry("gitlab", "staging.raw_gitlab"),
          java.util.Map.entry("jenkins", "staging.raw_jenkins"));

  /**
   * Resolves the staging table for a connector type (whitelist — never caller-composed SQL).
   *
   * @param connectorType the connector type
   * @return the fully qualified table name
   */
  public static String rawTable(String connectorType) {
    String table = RAW_TABLES.get(connectorType);
    if (table == null) {
      throw new IllegalArgumentException(
          "no raw staging table for connector type " + connectorType);
    }
    return table;
  }

  /** All raw staging tables (normalization reads every source). */
  public static java.util.Collection<String> rawTables() {
    return RAW_TABLES.values();
  }

  private static final String UPSERT =
      """
      INSERT INTO %s
        (tenant_id, connector_id, stream, natural_key, source_system, source_instance,
         external_id, op, fetch_kind, payload, content_hash)
      VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
      ON CONFLICT (tenant_id, connector_id, stream, natural_key) DO UPDATE SET
        source_system = EXCLUDED.source_system, source_instance = EXCLUDED.source_instance,
        external_id = EXCLUDED.external_id, op = EXCLUDED.op, fetch_kind = EXCLUDED.fetch_kind,
        payload = EXCLUDED.payload, content_hash = EXCLUDED.content_hash, ingested_at = now()
      WHERE %s.content_hash IS DISTINCT FROM EXCLUDED.content_hash
      """;

  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;

  public StagingRawRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** One staged record prepared for upsert (payload serialized + hashed). */
  public static final class RawUpsert {
    private final RawRecord record;
    private final String canonicalJson;
    private final byte[] contentHash;

    /**
     * Creates a prepared upsert.
     *
     * @param record the raw record
     * @param canonicalJson the key-sorted payload JSON
     * @param contentHash the SHA-256 of the canonical JSON
     */
    public RawUpsert(RawRecord record, String canonicalJson, byte[] contentHash) {
      this.record = record;
      this.canonicalJson = canonicalJson;
      this.contentHash = contentHash;
    }

    /** Returns the raw record. */
    public RawRecord record() {
      return record;
    }

    /** Returns the canonical payload JSON. */
    public String canonicalJson() {
      return canonicalJson;
    }

    /** Returns the content hash. */
    public byte[] contentHash() {
      return contentHash;
    }
  }

  /**
   * One staged row read back for normalization.
   *
   * @param op {@code upsert} or {@code delete} (staging.raw_&lt;connector&gt;.op) — {@link
   *     #readStream} only ever returns {@code upsert} rows; {@link #readStreamWithDeletes} returns
   *     both, for the one phase (work-item delete lifecycle, DEBT-020 item 3) that acts on deletes
   */
  public record StagedRow(
      String naturalKey,
      String sourceSystem,
      String sourceInstance,
      String externalId,
      String payload,
      String op) {}

  /**
   * Loads the connector's existing content hashes, keyed {@code stream|naturalKey}.
   *
   * @param connectorId the owning connector
   * @return existing hashes by stream + natural key
   */
  public Map<String, byte[]> contentHashes(String table, UUID connectorId) {
    Map<String, byte[]> hashes = new HashMap<>();
    jdbc.sql(
            "SELECT stream, natural_key, content_hash FROM "
                + table
                + " WHERE connector_id = :connectorId")
        .param("connectorId", connectorId)
        .query(
            (rs, rowNum) -> {
              hashes.put(streamKey(rs.getString(1), rs.getString(2)), rs.getBytes(3));
              return Boolean.TRUE;
            })
        .list();
    return hashes;
  }

  /**
   * Batch-upserts the changed records for a connector in one statement batch.
   *
   * @param connectorId the owning connector
   * @param changes the pre-classified inserts/updates (unchanged records are not sent)
   */
  public void upsertAll(String table, UUID connectorId, List<RawUpsert> changes) {
    String bare = table.substring(table.indexOf('.') + 1);
    jdbcTemplate.batchUpdate(
        UPSERT.formatted(table, bare),
        changes,
        changes.size(),
        (ps, change) -> {
          RawRecord r = change.record();
          ps.setObject(1, connectorId);
          ps.setString(2, r.stream());
          ps.setString(3, r.naturalKey());
          ps.setString(4, r.sourceSystem());
          ps.setString(5, r.sourceInstance());
          ps.setString(6, r.externalId());
          ps.setString(7, r.op().name().toLowerCase(Locale.ROOT));
          ps.setString(8, r.fetchKind().name().toLowerCase(Locale.ROOT));
          ps.setString(9, change.canonicalJson());
          ps.setBytes(10, change.contentHash());
        });
  }

  /**
   * Reads one stream's staged upsert rows in deterministic order (excludes {@code delete} rows).
   *
   * @param stream the logical source stream
   * @return the staged rows ordered by natural key
   */
  public List<StagedRow> readStream(String table, String stream) {
    return jdbc.sql(
            "SELECT natural_key, source_system, source_instance, external_id, payload::text, op"
                + " FROM "
                + table
                + " WHERE stream = :stream AND op = 'upsert' ORDER BY natural_key")
        .param("stream", stream)
        .query(
            (rs, rowNum) ->
                new StagedRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6)))
        .list();
  }

  /**
   * Reads ALL of one stream's staged rows — both {@code upsert} and {@code delete} — in
   * deterministic order. Used only by the work-item normalization phase, the one phase that acts on
   * delete assertions (DEBT-020 item 3); every other phase reads {@link #readStream} instead.
   *
   * @param stream the logical source stream
   * @return the staged rows ordered by natural key
   */
  public List<StagedRow> readStreamWithDeletes(String table, String stream) {
    return jdbc.sql(
            "SELECT natural_key, source_system, source_instance, external_id, payload::text, op"
                + " FROM "
                + table
                + " WHERE stream = :stream ORDER BY natural_key")
        .param("stream", stream)
        .query(
            (rs, rowNum) ->
                new StagedRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6)))
        .list();
  }

  /**
   * Builds the map key for {@link #contentHashes(String, UUID)}.
   *
   * @param stream the stream name
   * @param naturalKey the record's natural key
   * @return the composite lookup key
   */
  public static String streamKey(String stream, String naturalKey) {
    return stream + '|' + naturalKey;
  }
}
