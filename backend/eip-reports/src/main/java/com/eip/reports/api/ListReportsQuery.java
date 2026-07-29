/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

import org.jspecify.annotations.Nullable;

/**
 * Lists the current tenant's reports, cursor-paginated (APIDesign §1.4 keyset over {@code
 * (created_at desc, id desc)} with an opaque cursor), newest first.
 */
public interface ListReportsQuery {

  /** Default page size. */
  int DEFAULT_LIMIT = 20;

  /** Maximum page size; larger requests are clamped. */
  int MAX_LIMIT = 100;

  /**
   * Lists one page of the current tenant's reports.
   *
   * @param cursor opaque cursor from a previous page's {@code nextCursor}, or null for the first
   *     page
   * @param limit requested page size (clamped to [1, {@value #MAX_LIMIT}])
   * @return the page, newest first
   */
  ReportPageView list(@Nullable String cursor, int limit);
}
