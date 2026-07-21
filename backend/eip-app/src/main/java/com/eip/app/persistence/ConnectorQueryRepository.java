/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.app.api.ConnectorView;
import com.eip.app.api.PageView;
import com.eip.app.application.ListConnectorsQuery;
import com.eip.app.config.ApiCursorSigningProperties;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Connector-registry read adapter: keyset pagination over a whitelisted {@link ConnectorSort} with
 * the signed, expiring, opaque {@link ConnectorCursor} (DEBT-010), one row over-fetched to detect a
 * further page. Runs inside a tenant-bound read-only transaction, so RLS scopes the list to the
 * caller's tenant.
 */
@Repository
public class ConnectorQueryRepository implements ListConnectorsQuery {

  private final TenantTransactionRunner tx;
  private final JdbcClient jdbc;
  private final byte[] signingKey;
  private final Clock clock;

  /**
   * Creates the repository.
   *
   * @param tx the tenant-bound transaction runner
   * @param jdbc the JDBC client
   * @param signing the cursor-signing key source (DEBT-010)
   * @param clock the clock cursor issuance/expiry is computed against (a fixed clock in tests)
   */
  public ConnectorQueryRepository(
      TenantTransactionRunner tx,
      JdbcClient jdbc,
      ApiCursorSigningProperties signing,
      Clock clock) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.signingKey = Base64.getDecoder().decode(signing.cursorSigningKey());
    this.clock = clock;
  }

  @Override
  public PageView<ConnectorView> list(@Nullable String cursor, int limit, @Nullable String sort) {
    int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
    ConnectorSort resolvedSort = ConnectorSort.fromParam(sort);
    @Nullable ConnectorCursor after =
        ConnectorCursor.decode(cursor, resolvedSort, signingKey, clock);

    List<Row> rows = tx.readCurrent(() -> query(resolvedSort, after, pageSize + 1));

    boolean hasMore = rows.size() > pageSize;
    List<Row> page = hasMore ? rows.subList(0, pageSize) : rows;
    List<ConnectorView> items = page.stream().map(row -> row.view()).toList();
    @Nullable String nextCursor =
        hasMore ? encodeNext(resolvedSort, page.get(page.size() - 1)) : null;
    return new PageView<>(items, nextCursor, hasMore);
  }

  private String encodeNext(ConnectorSort sort, Row last) {
    @Nullable String name = sort.column().equals("name") ? last.view().name() : null;
    @Nullable Instant createdAt = sort.column().equals("created_at") ? last.createdAt() : null;
    return ConnectorCursor.encode(sort, name, createdAt, last.view().id(), signingKey, clock);
  }

  private List<Row> query(ConnectorSort sort, @Nullable ConnectorCursor after, int fetch) {
    String direction = sort.ascending() ? "ASC" : "DESC";
    String base =
        "SELECT id, type, name, status, simulation, created_at FROM core.connector"
            + " WHERE deleted_at IS NULL";
    String orderBy = " ORDER BY " + sort.column() + " " + direction + ", id " + direction;
    JdbcClient.StatementSpec spec;
    if (after == null) {
      spec = jdbc.sql(base + orderBy + " LIMIT :limit");
    } else {
      String comparator = sort.ascending() ? ">" : "<";
      Object pivot = pivotValue(sort, after);
      spec =
          jdbc.sql(
                  base
                      + " AND ("
                      + sort.column()
                      + ", id) "
                      + comparator
                      + " (:pivot, :id)"
                      + orderBy
                      + " LIMIT :limit")
              .param("pivot", pivot)
              .param("id", after.id());
    }
    return spec.param("limit", fetch)
        .query(
            (rs, rowNum) ->
                new Row(
                    new ConnectorView(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getBoolean("simulation")),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * The keyset pivot value for the row a cursor points past: the previous page's last {@code name}
   * or {@code created_at}, matching {@code sort}'s column. {@link ConnectorCursor#decode} always
   * returns a cursor whose populated field matches its own embedded sort, and {@code sort} here is
   * that same embedded sort (re-checked against the request in {@code decode}), so exactly one of
   * {@code after.name()}/{@code after.createdAt()} is non-null.
   */
  private static Object pivotValue(ConnectorSort sort, ConnectorCursor after) {
    if (sort.column().equals("name")) {
      @Nullable String name = after.name();
      if (name == null) {
        throw new IllegalStateException("cursor for sort " + sort + " carries no name pivot");
      }
      return name;
    }
    @Nullable Instant createdAt = after.createdAt();
    if (createdAt == null) {
      throw new IllegalStateException("cursor for sort " + sort + " carries no createdAt pivot");
    }
    return Timestamp.from(createdAt);
  }

  /**
   * One raw list row: the public {@link ConnectorView} plus {@code created_at} for cursor encoding.
   */
  private record Row(ConnectorView view, Instant createdAt) {}
}
