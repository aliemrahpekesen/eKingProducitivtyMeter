/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.persistence;

import com.eip.core.domain.UuidV7Generator;
import com.eip.core.error.InternalException;
import com.eip.tenancy.audit.api.AuditOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

/**
 * {@code JdbcClient} adapter over {@code audit.audit_event} (V1 baseline; DatabasePlan §3). Every
 * method runs on the caller's tenant-bound transaction/connection (RLS enforces the tenant filter —
 * {@code current_setting('app.tenant_id')} has no default and errors if unbound,
 * R__rls_policies.sql header comment) except {@link #tryAcquireChainerLock}, which is likewise
 * tenant-scoped via the same GUC embedded in its advisory-lock key.
 */
@Repository
public class AuditEventRepository {

  /**
   * This repository's Postgres advisory-lock namespace (the first key of {@code
   * pg_try_advisory_xact_lock(int, int)}). An arbitrary constant private to the audit chainer; no
   * other subsystem in this codebase takes advisory locks yet (verified by repo-wide search), so
   * collision is a future-proofing concern only — if another feature adopts advisory locks later,
   * it must pick a different namespace constant.
   */
  private static final int CHAINER_LOCK_NAMESPACE = 0x41554431; // "AUD1" in hex, arbitrary

  private final JdbcClient jdbc;
  private final DataSource dataSource;
  private final ObjectMapper mapper;
  private final UuidV7Generator ids;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JDBC client (joins the caller's tenant-bound transaction)
   * @param dataSource the application datasource — needed only to open a savepoint around the
   *     INSERT (see {@link #insert}), joining whatever transaction is already active via {@link
   *     DataSourceUtils}, never opening a second connection
   * @param mapper the shared {@link ObjectMapper} used to serialize {@code detail} to jsonb
   * @param ids the platform's UUIDv7 id generator (the table has no {@code DEFAULT} on {@code id})
   */
  public AuditEventRepository(
      JdbcClient jdbc, DataSource dataSource, ObjectMapper mapper, UuidV7Generator ids) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
    this.mapper = mapper;
    this.ids = ids;
  }

  /**
   * Inserts one row with {@code prev_hash}/{@code hash} left {@code NULL} (the async chainer sets
   * them later). The INSERT runs inside a JDBC savepoint on the caller's own connection: if it
   * fails, only the audit write rolls back (to the savepoint) — the caller's enclosing business
   * transaction is untouched and can still commit. Without this, a plain try/catch would still
   * leave the enclosing Postgres transaction "aborted" for every subsequent statement (Postgres
   * poisons a transaction after any failed statement until rolled back), which would silently turn
   * a logging-only failure into a business-transaction failure — exactly what callers must never
   * see.
   *
   * @param action the dotted event id
   * @param outcome {@code SUCCESS} or {@code FAILURE}
   * @param actorMemberId the acting member, or {@code null}
   * @param traceId the active OTel trace id, or {@code null} if none was active
   * @param detail the full {@code detail} map ({@code category}/{@code actorType} already merged in
   *     by the caller)
   * @return the generated audit id
   * @throws RuntimeException if the INSERT fails; callers (only {@code AuditService}) must catch
   *     this and never let it propagate further
   */
  public UUID insert(
      String action,
      AuditOutcome outcome,
      @Nullable UUID actorMemberId,
      @Nullable String traceId,
      Map<String, Object> detail) {
    UUID id = ids.generate();
    String detailJson = writeJson(detail);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    Savepoint savepoint;
    try {
      savepoint = connection.setSavepoint();
    } catch (SQLException e) {
      DataSourceUtils.releaseConnection(connection, dataSource);
      throw new InternalException("failed to open audit insert savepoint", e);
    }
    try {
      jdbc.sql(
              """
              INSERT INTO audit.audit_event
                (id, tenant_id, actor_member_id, action, outcome, trace_id, detail)
              VALUES
                (:id, current_setting('app.tenant_id')::uuid, :actorMemberId, :action, :outcome,
                 :traceId, :detail::jsonb)
              """)
          .param("id", id)
          .param("actorMemberId", actorMemberId)
          .param("action", action)
          .param("outcome", outcome.name())
          .param("traceId", traceId)
          .param("detail", detailJson)
          .update();
      return id;
    } catch (RuntimeException e) {
      try {
        connection.rollback(savepoint);
      } catch (SQLException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw e;
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  /**
   * Attempts to acquire this tenant's chainer lock for the remainder of the current transaction
   * (auto-released on commit/rollback). Non-blocking: returns {@code false} immediately if another
   * writer (another instance, or an overlapping sweep) already holds it, so a sweep simply skips a
   * busy tenant rather than waiting.
   *
   * @return {@code true} if the lock was acquired
   */
  public boolean tryAcquireChainerLock() {
    Boolean acquired =
        jdbc.sql(
                "SELECT pg_try_advisory_xact_lock(:namespace,"
                    + " hashtext(current_setting('app.tenant_id')))")
            .param("namespace", CHAINER_LOCK_NAMESPACE)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(acquired);
  }

  /**
   * The caller tenant's oldest not-yet-chained rows, in strict chain order.
   *
   * @param limit the maximum rows to return
   * @return unchained rows, oldest {@code (occurred_at, id)} first
   */
  public List<ChainCandidateRow> findUnchainedOrdered(int limit) {
    return jdbc.sql(
            """
            SELECT id, occurred_at, actor_member_id, action, outcome, trace_id,
                   detail::text AS detail_json
            FROM audit.audit_event
            WHERE tenant_id = current_setting('app.tenant_id')::uuid AND hash IS NULL
            ORDER BY occurred_at, id
            LIMIT :limit
            """)
        .param("limit", limit)
        .query(AuditEventRepository::mapCandidateRow)
        .list();
  }

  /**
   * The caller tenant's most recently chained row's {@code hash} — the {@code prev_hash} the next
   * unchained row must link to. Empty if the tenant has no chained rows yet (its next row is the
   * chain's genesis).
   *
   * @return the last chained hash, if any
   */
  public Optional<byte[]> findLastChainedHash() {
    return jdbc.sql(
            """
            SELECT hash FROM audit.audit_event
            WHERE tenant_id = current_setting('app.tenant_id')::uuid AND hash IS NOT NULL
            ORDER BY occurred_at DESC, id DESC
            LIMIT 1
            """)
        .query((rs, rowNum) -> rs.getBytes("hash"))
        .optional();
  }

  /**
   * Sets a row's chain hashes. Guarded by {@code hash IS NULL} so a stray double-chain attempt
   * (defense in depth alongside {@link #tryAcquireChainerLock}) is a silent no-op, not a corrupting
   * overwrite.
   *
   * @param id the row id
   * @param occurredAt the row's partition key (composite PK is {@code (id, occurred_at)})
   * @param prevHash the previous row's hash, or this tenant's genesis hash for the first row
   * @param hash {@code SHA-256(prevHash || canonical(row))}
   */
  public void setChain(UUID id, Instant occurredAt, byte[] prevHash, byte[] hash) {
    jdbc.sql(
            """
            UPDATE audit.audit_event SET prev_hash = :prevHash, hash = :hash
            WHERE id = :id AND occurred_at = :occurredAt AND hash IS NULL
            """)
        .param("prevHash", prevHash)
        .param("hash", hash)
        .param("id", id)
        .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
        .update();
  }

  /**
   * The caller tenant's already-chained rows, in chain order — the verifier's full per-tenant walk.
   *
   * @param limit the maximum rows to return (a defensive cap; see this module's root package-info
   *     "volume-scaling is future" note — a tenant with more chained rows than this is verified
   *     only up to the cap in one sweep)
   * @return chained rows, oldest {@code (occurred_at, id)} first
   */
  public List<ChainedRow> findChainedOrdered(int limit) {
    return jdbc.sql(
            """
            SELECT id, occurred_at, actor_member_id, action, outcome, trace_id,
                   detail::text AS detail_json, prev_hash, hash
            FROM audit.audit_event
            WHERE tenant_id = current_setting('app.tenant_id')::uuid AND hash IS NOT NULL
            ORDER BY occurred_at, id
            LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new ChainedRow(
                    mapCandidateRow(rs, rowNum), rs.getBytes("prev_hash"), rs.getBytes("hash")))
        .list();
  }

  private static ChainCandidateRow mapCandidateRow(ResultSet rs, int rowNum) throws SQLException {
    return new ChainCandidateRow(
        rs.getObject("id", UUID.class),
        // pgjdbc's getObject(index, Instant.class) does not reliably return an Instant (mirrors the
        // lesson in ServiceTokenRepository); read via getTimestamp and convert explicitly.
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getObject("actor_member_id", UUID.class),
        rs.getString("action"),
        rs.getString("outcome"),
        rs.getString("trace_id"),
        rs.getString("detail_json"));
  }

  private String writeJson(Map<String, Object> detail) {
    try {
      return mapper.writeValueAsString(detail);
    } catch (JsonProcessingException e) {
      throw new InternalException("failed to serialize audit detail", e);
    }
  }

  /**
   * One row read back for chaining — the fields {@link
   * com.eip.tenancy.audit.application.AuditRecordCanonicalizer} hashes, before {@code prev_hash}/
   * {@code hash} exist.
   *
   * @param id the row id
   * @param occurredAt the row's timestamp (partition key)
   * @param actorMemberId the acting member, or {@code null}
   * @param action the dotted event id
   * @param outcome {@code "SUCCESS"} or {@code "FAILURE"} (raw column text)
   * @param traceId the row's {@code trace_id}, or {@code null}
   * @param detailJson the {@code detail} jsonb column, as text
   */
  public record ChainCandidateRow(
      UUID id,
      Instant occurredAt,
      @Nullable UUID actorMemberId,
      String action,
      String outcome,
      @Nullable String traceId,
      String detailJson) {}

  /**
   * One already-chained row, for the verifier.
   *
   * @param fields the same fields a candidate row carries
   * @param prevHash the stored {@code prev_hash}
   * @param hash the stored {@code hash}
   */
  @SuppressWarnings("ArrayRecordComponent") // internal DTO, never used as an equals/hashCode key
  public record ChainedRow(ChainCandidateRow fields, byte[] prevHash, byte[] hash) {

    /**
     * The row id (delegates to {@link #fields}).
     *
     * @return the row id
     */
    public UUID id() {
      return fields.id();
    }
  }
}
