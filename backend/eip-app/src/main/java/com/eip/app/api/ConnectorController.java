/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.ListConnectorsQuery;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connector-administration read surface. A pure DTO adapter (BackendPlan §2.4): parameter
 * binding only; cursor decoding, limit clamping, and the keyset query live in the {@link
 * ListConnectorsQuery} adapter. Responses use the cursor-paginated {@link PageView} envelope
 * (APIDesign §1.4).
 */
@RestController
@RequestMapping("/api/v1")
public class ConnectorController {

  private final ListConnectorsQuery connectors;

  public ConnectorController(ListConnectorsQuery connectors) {
    this.connectors = connectors;
  }

  /**
   * Lists the current tenant's connectors, cursor-paginated.
   *
   * @param cursor opaque cursor from a previous page's {@code nextCursor}, or absent for the first
   *     page
   * @param limit page size, clamped to [1, {@value ListConnectorsQuery#MAX_LIMIT}] (default {@value
   *     ListConnectorsQuery#DEFAULT_LIMIT})
   * @return a page of the tenant's connectors, ordered by {@code (name, id)}
   */
  @GetMapping("/connectors")
  public PageView<ConnectorView> connectors(
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "limit", defaultValue = "" + ListConnectorsQuery.DEFAULT_LIMIT)
          int limit) {
    return connectors.list(cursor, limit);
  }
}
