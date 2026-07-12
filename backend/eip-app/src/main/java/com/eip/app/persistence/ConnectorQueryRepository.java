/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.app.api.ConnectorView;
import com.eip.app.api.PageView;
import com.eip.app.application.ListConnectorsQuery;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Connector-registry read adapter: keyset pagination over {@code (name, id)} with the opaque {@link
 * ConnectorCursor}, one row over-fetched to detect a further page. Runs inside a tenant-bound
 * read-only transaction, so RLS scopes the list to the caller's tenant.
 */
@Repository
public class ConnectorQueryRepository implements ListConnectorsQuery {

  private final TenantTransactionRunner tx;
  private final JdbcClient jdbc;

  public ConnectorQueryRepository(TenantTransactionRunner tx, JdbcClient jdbc) {
    this.tx = tx;
    this.jdbc = jdbc;
  }

  @Override
  public PageView<ConnectorView> list(@Nullable String cursor, int limit) {
    int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
    @Nullable ConnectorCursor after = ConnectorCursor.decode(cursor);

    List<ConnectorView> rows = tx.readCurrent(() -> query(after, pageSize + 1));

    boolean hasMore = rows.size() > pageSize;
    List<ConnectorView> page = hasMore ? rows.subList(0, pageSize) : rows;
    @Nullable String nextCursor =
        hasMore ? ConnectorCursor.encode(page.get(page.size() - 1)) : null;
    return new PageView<>(List.copyOf(page), nextCursor, hasMore);
  }

  private List<ConnectorView> query(@Nullable ConnectorCursor after, int fetch) {
    String base =
        "SELECT id, type, name, status, simulation FROM core.connector WHERE deleted_at IS NULL";
    JdbcClient.StatementSpec spec =
        (after == null)
            ? jdbc.sql(base + " ORDER BY name, id LIMIT :limit")
            : jdbc.sql(base + " AND (name, id) > (:name, :id) ORDER BY name, id LIMIT :limit")
                .param("name", after.name())
                .param("id", after.id());
    return spec.param("limit", fetch)
        .query(
            (rs, rowNum) ->
                new ConnectorView(
                    rs.getObject("id", UUID.class),
                    rs.getString("type"),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getBoolean("simulation")))
        .list();
  }
}
