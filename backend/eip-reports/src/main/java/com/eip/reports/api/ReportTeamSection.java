/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TrendPointView;
import java.util.List;
import java.util.UUID;

/**
 * One team's section of the report: its current friction + weekly trend history, its currently
 * fired recommendations, and its in-flight counts — all reused verbatim from the composed analytics
 * views (TASK-0022; never recomputed). Team-level only (Law 6 / NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param frictionScore the team's current computed friction score (0–100), from the friction
 *     summary — not derived from {@code points}
 * @param dominantCause the team's current largest waiting sink ({@code BLOCKED} | {@code
 *     REVIEW_WAIT} | {@code NONE})
 * @param points the team's weekly metric trend within the report's window, ascending by {@code
 *     weekStart}; empty when the team resolved nothing in the window
 * @param recommendations the team's currently fired recommendations; empty for a healthy team
 * @param inFlightCount number of the team's currently unresolved work items
 * @param blockedInFlightCount number of those unresolved items currently blocked
 */
public record ReportTeamSection(
    UUID teamId,
    String teamName,
    int frictionScore,
    String dominantCause,
    List<TrendPointView> points,
    List<RecommendationView> recommendations,
    int inFlightCount,
    int blockedInFlightCount) {}
