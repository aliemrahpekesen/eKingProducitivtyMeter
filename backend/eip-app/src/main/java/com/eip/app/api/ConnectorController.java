/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.tenant.TenantScopedJdbc;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connector-administration read surface: lists the tenant's registered connectors with their
 * status. Read tenant-scoped under RLS — a tenant never sees another tenant's connectors. Responses
 * use the cursor-paginated {@link PageView} envelope (APIDesign §1.4): keyset over {@code (name,
 * id)} with an opaque cursor.
 */
@RestController
@RequestMapping("/api/v1")
public class ConnectorController {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  private final TenantScopedJdbc jdbc;

  public ConnectorController(TenantScopedJdbc jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Lists the current tenant's connectors, cursor-paginated.
   *
   * @param cursor opaque cursor from a previous page's {@code nextCursor}, or absent for the first
   *     page
   * @param limit page size, clamped to [1, {@value #MAX_LIMIT}] (default {@value #DEFAULT_LIMIT})
   * @return a page of the tenant's connectors, ordered by {@code (name, id)}
   */
  @GetMapping("/connectors")
  public PageView<ConnectorView> connectors(
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
    int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
    @Nullable ConnectorCursor after = ConnectorCursor.decode(cursor);

    // Fetch one extra row to detect whether a further page exists (hasMore).
    List<ConnectorView> rows = jdbc.read(client -> query(client, after, pageSize + 1));

    boolean hasMore = rows.size() > pageSize;
    List<ConnectorView> page = hasMore ? rows.subList(0, pageSize) : rows;
    @Nullable String nextCursor =
        hasMore ? ConnectorCursor.encode(page.get(page.size() - 1)) : null;
    return new PageView<>(List.copyOf(page), nextCursor, hasMore);
  }

  private static List<ConnectorView> query(
      JdbcClient client, @Nullable ConnectorCursor after, int fetch) {
    String base =
        "SELECT id, type, name, status, simulation FROM core.connector WHERE deleted_at IS NULL";
    JdbcClient.StatementSpec spec =
        (after == null)
            ? client.sql(base + " ORDER BY name, id LIMIT :limit")
            : client
                .sql(base + " AND (name, id) > (:name, :id) ORDER BY name, id LIMIT :limit")
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
