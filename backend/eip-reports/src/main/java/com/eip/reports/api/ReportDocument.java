/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The full deterministic content of one {@code EXEC_SUMMARY} report (TASK-0022, ADR-023): derived
 * only from the analytics module's composed query reads (trends, recommendations, in-flight work,
 * friction summary) for one {@code weeks}-wide window, plus the one sanctioned wall-clock read,
 * {@code generatedAt} — bookkeeping only, never an input to any computed value (the one exception
 * is the empty-data fallback documented on {@code periodStart}/{@code periodEnd}). Recomputing this
 * document from the same underlying data reproduces the same {@code totals}/{@code teams} content
 * byte-for-byte, modulo {@code generatedAt}. Team-level only (Law 6 / NFR-071).
 *
 * @param reportVersion the document schema version (currently always {@link #CURRENT_VERSION})
 * @param title the report title ({@code "Engineering Flow Report — {periodStart}..{periodEnd}"},
 *     en-US)
 * @param periodStart the inclusive start of the reported data window: the earliest resolved week's
 *     Monday (UTC midnight) across every team's trend points, or — only when no team has resolved
 *     anything in the requested window — {@code generatedAt}'s UTC date minus {@code weeks} weeks
 * @param periodEnd the exclusive end of the reported data window: the latest resolved week's Monday
 *     + 7 days (UTC midnight), or the empty-data fallback's {@code generatedAt}'s UTC date
 * @param weeks the requested window width, in weeks
 * @param generatedAt when this document was assembled (bookkeeping only)
 * @param metricVersion the friction metric's computed version (e.g. {@code
 *     engineering_friction_v0.1}), or {@code null} if nothing has been computed yet
 * @param totals the report's headline aggregates
 * @param teams per-team sections, worst-first by current friction score
 */
public record ReportDocument(
    int reportVersion,
    String title,
    Instant periodStart,
    Instant periodEnd,
    int weeks,
    Instant generatedAt,
    @Nullable String metricVersion,
    ReportTotals totals,
    List<ReportTeamSection> teams) {

  /** The document schema version this generator writes. */
  public static final int CURRENT_VERSION = 1;
}
