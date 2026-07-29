/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.api.ConnectorView;
import com.eip.app.api.PageView;
import org.jspecify.annotations.Nullable;

/**
 * Lists the current tenant's connectors, cursor-paginated (APIDesign §1.4 keyset over a whitelisted
 * {@code sort} with an opaque, signed, expiring cursor — DEBT-010). Pagination mechanics — cursor
 * decoding, limit clamping, has-more detection — belong to the adapter, not the controller.
 */
public interface ListConnectorsQuery {

  /** Default page size. */
  int DEFAULT_LIMIT = 50;

  /** Maximum page size; larger requests are clamped. */
  int MAX_LIMIT = 200;

  /** The default {@code sort} query value, matching the pre-DEBT-010 fixed {@code (name, id)}. */
  String DEFAULT_SORT = "name";

  /**
   * Lists one page of the current tenant's connectors.
   *
   * @param cursor opaque cursor from a previous page's {@code nextCursor}, or null for the first
   *     page
   * @param limit requested page size (clamped to [1, {@value #MAX_LIMIT}])
   * @param sort the whitelisted sort (see {@code ConnectorSort}); {@code null}/blank defaults to
   *     {@value #DEFAULT_SORT}
   * @return the page, ordered by the resolved sort with {@code id} as tie-breaker
   */
  PageView<ConnectorView> list(@Nullable String cursor, int limit, @Nullable String sort);
}
