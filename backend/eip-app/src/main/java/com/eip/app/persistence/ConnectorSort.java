/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.core.error.ValidationException;
import org.jspecify.annotations.Nullable;

/**
 * The whitelisted {@code sort} values for {@code GET /api/v1/connectors} (APIDesign §1.4: "{@code
 * sort} (whitelisted fields)", DEBT-010). A leading {@code -} means descending; {@link #NAME_ASC}
 * (query value {@code "name"}) is the default, matching the pre-DEBT-010 fixed {@code ORDER BY
 * name, id}. {@link #column} is the underlying {@code core.connector} column the keyset comparison
 * runs against; {@link ConnectorQueryRepository} ties every ordering to {@code id} as the
 * tie-breaker, ascending/descending with the primary column so the composite keyset comparison
 * stays a single consistent direction.
 */
public enum ConnectorSort {
  /** Default: ascending by display name (pre-DEBT-010 behavior). */
  NAME_ASC("name", "name", true),
  /** Descending by display name. */
  NAME_DESC("-name", "name", false),
  /** Ascending by registration time (oldest first). */
  CREATED_AT_ASC("createdAt", "created_at", true),
  /** Descending by registration time (newest first). */
  CREATED_AT_DESC("-createdAt", "created_at", false);

  private final String param;
  private final String column;
  private final boolean ascending;

  ConnectorSort(String param, String column, boolean ascending) {
    this.param = param;
    this.column = column;
    this.ascending = ascending;
  }

  /**
   * Resolves the client-supplied {@code sort} query param.
   *
   * @param raw the raw {@code sort} value, or {@code null}/blank for the default
   * @return the resolved sort
   * @throws ValidationException if {@code raw} is non-blank and not one of the whitelisted values
   */
  public static ConnectorSort fromParam(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return NAME_ASC;
    }
    for (ConnectorSort sort : values()) {
      if (sort.param.equals(raw)) {
        return sort;
      }
    }
    StringBuilder allowed = new StringBuilder();
    for (ConnectorSort sort : values()) {
      if (!allowed.isEmpty()) {
        allowed.append(", ");
      }
      allowed.append(sort.param);
    }
    throw new ValidationException("unknown sort '" + raw + "'; allowed values: " + allowed);
  }

  /**
   * The query-param spelling.
   *
   * @return the {@code sort} value a client supplies to select this ordering
   */
  public String param() {
    return param;
  }

  /**
   * The underlying {@code core.connector} column.
   *
   * @return the snake_case column name
   */
  public String column() {
    return column;
  }

  /**
   * The sort direction.
   *
   * @return {@code true} for ascending, {@code false} for descending
   */
  public boolean ascending() {
    return ascending;
  }
}
