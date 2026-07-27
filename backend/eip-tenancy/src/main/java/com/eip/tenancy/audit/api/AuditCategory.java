/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.api;

import java.util.Locale;

/**
 * Event-taxonomy categories DEBT-024 Wave 3B's planned call-site retrofit populates (SecurityModel
 * §11's table lists eight: {@code auth}, {@code access}, {@code admin}, {@code secrets}, {@code
 * ai}, {@code mcp}, {@code data}, {@code platform}). Only the four in that wave's declared scope
 * are modeled here; {@code ai}/{@code mcp}/{@code data}/{@code platform} are added by their owning
 * modules' own future audit-instrumentation work, each reviewed on its own — not a gap, just not
 * yet claimed.
 *
 * <p>Stored in {@code detail.category} (lowercase wire value) since V1's {@code audit.audit_event}
 * has no dedicated column — see this module's root package-info for the full field-mapping note.
 */
public enum AuditCategory {
  AUTH,
  ACCESS,
  ADMIN,
  SECRETS;

  /**
   * The lowercase wire value stored in {@code detail.category} (matches SecurityModel §11's table).
   *
   * @return the lowercase category name
   */
  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
