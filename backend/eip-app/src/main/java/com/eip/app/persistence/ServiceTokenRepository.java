/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.core.domain.UuidV7Generator;
import com.eip.core.error.InternalException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

/**
 * Adapter over {@code core.service_token} (V10, ADR-025). Deliberately NOT RLS-backed — see that
 * migration's header comment and {@code R__rls_policies.sql}'s trailing note — every method here
 * filters explicitly by {@code tenant_id} (tenant-scoped rows) or {@code tenant_id IS NULL}
 * (platform-scoped rows); {@link com.eip.app.application.ServiceTokenService} decides which and
 * enforces the PLATFORM_ADMIN-only gate on the platform-scoped path before ever calling here. This
 * class is the ONLY code permitted to query {@code core.service_token} directly (ADR-025's stated
 * mitigation for the table having no RLS backstop).
 *
 * <p>{@link #findByHash} is the authentication lookup: it runs before any tenant is known (that is
 * the point of the lookup) and is therefore unscoped by design, matched only by the token's unique
 * hash — a 192-bit-entropy value, so exposing "does this hash exist, and for which tenant/role" to
 * the lookup itself leaks nothing exploitable.
 *
 * <p>Implements {@link ServiceTokenStore} — the seam {@code ServiceTokenService} depends on, so its
 * unit test can use a hand-rolled in-memory fake instead of mocking this class (TestingStrategy
 * §2).
 */
@Repository
public class ServiceTokenRepository implements ServiceTokenStore {

  private final JdbcClient jdbc;
  private final DataSource dataSource;
  private final UuidV7Generator ids;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JDBC client
   * @param dataSource the application datasource (needed only to build the {@code text[]} {@code
   *     permission_subset} bind value via {@link Connection#createArrayOf}; joins whatever
   *     transaction is already active via {@link DataSourceUtils}, never opens a second connection)
   * @param ids the platform's UUIDv7 id generator (the table has no {@code DEFAULT} on {@code id})
   */
  public ServiceTokenRepository(JdbcClient jdbc, DataSource dataSource, UuidV7Generator ids) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
    this.ids = ids;
  }

  /**
   * Inserts a new service token row. The raw token itself is never passed here — only its hash and
   * clear-text prefix (SecurityModel §3).
   *
   * @param tenantId the owning tenant, or {@code null} for a platform-scoped token
   * @param name operator-facing label
   * @param tokenPrefix the first characters after {@code eipt_}, stored in the clear for
   *     admin-panel identification
   * @param tokenHash the SHA-256 hex digest of the full raw token
   * @param role the bound {@code Role} enum name
   * @param permissionSubset the narrowed permission subset (enum names), or empty for "all of the
   *     role's permissions"
   * @param createdByMemberId the creating member, if known
   * @param expiresAt the token's expiry instant
   * @return the new row's id
   */
  @Override
  public UUID insert(
      @Nullable UUID tenantId,
      String name,
      String tokenPrefix,
      String tokenHash,
      String role,
      List<String> permissionSubset,
      @Nullable UUID createdByMemberId,
      Instant expiresAt) {
    UUID id = ids.generate();
    jdbc.sql(
            """
            INSERT INTO core.service_token
              (id, tenant_id, name, token_prefix, token_hash, role, permission_subset,
               created_by_member_id, expires_at)
            VALUES
              (:id, :tenantId, :name, :tokenPrefix, :tokenHash, :role, :permissionSubset,
               :createdByMemberId, :expiresAt)
            """)
        .param("id", id)
        .param("tenantId", tenantId)
        .param("name", name)
        .param("tokenPrefix", tokenPrefix)
        .param("tokenHash", tokenHash)
        .param("role", role)
        .param("permissionSubset", toSqlArray(permissionSubset))
        .param("createdByMemberId", createdByMemberId)
        // pgjdbc cannot infer a SQL type for a bare java.time.Instant (only the java.time types
        // JDBC 4.2 standardizes — OffsetDateTime included — bind directly); converting at the UTC
        // offset round-trips correctly through `timestamptz`.
        .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
        .update();
    return id;
  }

  /**
   * Looks up a live (non-revoked, non-expired) token by its hash — the per-request authentication
   * path.
   *
   * @param tokenHash the SHA-256 hex digest of the presented raw token
   * @return the resolved auth record, empty if the hash is unknown, revoked, or expired (never
   *     distinguished further — see {@code ServiceTokenAuthenticationFilter})
   */
  public Optional<AuthRecord> findByHash(String tokenHash) {
    return jdbc.sql(
            """
            SELECT id, tenant_id, role, permission_subset
            FROM core.service_token
            WHERE token_hash = :tokenHash AND revoked_at IS NULL AND expires_at > now()
            """)
        .param("tokenHash", tokenHash)
        .query(this::mapAuthRow)
        .optional();
  }

  /**
   * Lists a tenant's own service tokens, newest first.
   *
   * @param tenantId the tenant
   * @return the tenant's tokens (no raw token or hash)
   */
  @Override
  public List<ServiceTokenRow> listForTenant(UUID tenantId) {
    return jdbc.sql(
            """
            SELECT id, name, token_prefix, role, permission_subset, expires_at, last_used_at,
                   revoked_at, created_at
            FROM core.service_token WHERE tenant_id = :tenantId ORDER BY created_at DESC, id
            """)
        .param("tenantId", tenantId)
        .query(this::mapRow)
        .list();
  }

  /**
   * Lists platform-scoped service tokens, newest first (PLATFORM_ADMIN callers only — enforced by
   * {@code ServiceTokenService} before this is called).
   *
   * @return the platform-scoped tokens (no raw token or hash)
   */
  @Override
  public List<ServiceTokenRow> listPlatformScoped() {
    return jdbc.sql(
            """
            SELECT id, name, token_prefix, role, permission_subset, expires_at, last_used_at,
                   revoked_at, created_at
            FROM core.service_token WHERE tenant_id IS NULL ORDER BY created_at DESC, id
            """)
        .query(this::mapRow)
        .list();
  }

  /**
   * Revokes a tenant-scoped token owned by the given tenant.
   *
   * @param id the token id
   * @param tenantId the owning tenant (a mismatched tenant sees zero rows updated, never another
   *     tenant's token)
   * @return rows updated (0 if not found under this tenant, or already revoked — revocation is
   *     idempotent either way from the caller's perspective)
   */
  @Override
  public int revokeForTenant(UUID id, UUID tenantId) {
    return jdbc.sql(
            """
            UPDATE core.service_token SET revoked_at = now()
            WHERE id = :id AND tenant_id = :tenantId AND revoked_at IS NULL
            """)
        .param("id", id)
        .param("tenantId", tenantId)
        .update();
  }

  /**
   * Revokes a platform-scoped token (PLATFORM_ADMIN callers only — enforced by {@code
   * ServiceTokenService} before this is called).
   *
   * @param id the token id
   * @return rows updated (0 if not found among platform-scoped tokens, or already revoked)
   */
  @Override
  public int revokePlatformScoped(UUID id) {
    return jdbc.sql(
            """
            UPDATE core.service_token SET revoked_at = now()
            WHERE id = :id AND tenant_id IS NULL AND revoked_at IS NULL
            """)
        .param("id", id)
        .update();
  }

  /**
   * Best-effort last-used timestamp update. Failures here MUST NOT break request authentication —
   * callers catch and log, never propagate (SecurityModel §3: last-used tracked, but a tracking
   * failure is not an auth failure).
   *
   * @param id the token id
   */
  public void touchLastUsed(UUID id) {
    jdbc.sql("UPDATE core.service_token SET last_used_at = now() WHERE id = :id")
        .param("id", id)
        .update();
  }

  private @Nullable Array toSqlArray(List<String> permissionSubset) {
    if (permissionSubset.isEmpty()) {
      return null; // NULL == "all of the role's permissions" (V10 column comment).
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      return connection.createArrayOf("text", permissionSubset.toArray(new String[0]));
    } catch (SQLException e) {
      throw new InternalException("failed to build permission_subset array", e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private AuthRecord mapAuthRow(ResultSet rs, int rowNum) throws SQLException {
    return new AuthRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("role"),
        readPermissionSubset(rs));
  }

  private ServiceTokenRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ServiceTokenRow(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("token_prefix"),
        rs.getString("role"),
        readPermissionSubset(rs),
        requireInstant(rs, "expires_at"),
        readInstant(rs, "last_used_at"),
        rs.getObject("revoked_at") != null,
        requireInstant(rs, "created_at"));
  }

  private static @Nullable Instant readInstant(ResultSet rs, String column) throws SQLException {
    // pgjdbc's getObject(index, Instant.class) does not reliably return an Instant (observed
    // returning java.sql.Timestamp, which then fails a hard cast) — read via getTimestamp and
    // convert explicitly instead of trusting JdbcUtils#getResultSetValue's requiredType coercion.
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Instant requireInstant(ResultSet rs, String column) throws SQLException {
    Instant value = readInstant(rs, column);
    if (value == null) {
      throw new InternalException("column " + column + " was unexpectedly NULL");
    }
    return value;
  }

  private static List<String> readPermissionSubset(ResultSet rs) throws SQLException {
    Array array = rs.getArray("permission_subset");
    if (array == null) {
      return List.of();
    }
    String[] values = (String[]) array.getArray();
    return List.of(values);
  }

  /**
   * The authentication-lookup projection (never carries the hash or prefix's siblings needed to
   * reconstruct anything — just enough to build an {@code EipPrincipal}).
   *
   * @param id the token row id
   * @param tenantId the bound tenant, or {@code null} for platform-scoped
   * @param role the bound {@code Role} enum name
   * @param permissionSubset the narrowed permission subset (enum names), empty meaning "all"
   */
  public record AuthRecord(
      UUID id, @Nullable UUID tenantId, String role, List<String> permissionSubset) {}

  /**
   * One admin-listing row. Never carries the raw token or its hash.
   *
   * @param id the token row id
   * @param name operator-facing label
   * @param tokenPrefix the clear-text prefix
   * @param role the bound {@code Role} enum name
   * @param permissionSubset the narrowed permission subset (enum names), empty meaning "all"
   * @param expiresAt expiry instant
   * @param lastUsedAt last successful-use instant, or {@code null} if never used
   * @param revoked whether the token has been revoked
   * @param createdAt creation instant
   */
  public record ServiceTokenRow(
      UUID id,
      String name,
      String tokenPrefix,
      String role,
      List<String> permissionSubset,
      Instant expiresAt,
      @Nullable Instant lastUsedAt,
      boolean revoked,
      Instant createdAt) {}
}
