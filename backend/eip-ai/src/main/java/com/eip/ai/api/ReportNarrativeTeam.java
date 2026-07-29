/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TrendPointView;
import java.util.List;
import java.util.UUID;

/**
 * One team's section of a report, as fed to {@link NarrateReportUseCase} (ADR-024). Field-for-field
 * mirror of {@code com.eip.reports.api.ReportTeamSection} minus the in-flight counts (not part of
 * the narrative) — see {@link ReportNarrativeInput}'s javadoc for why {@code eip-app} copies this
 * across the module boundary rather than {@code eip-ai} reading {@code com.eip.reports.api}
 * directly. {@code eip-ai} already depends on {@code eip-analytics}, so {@link TrendPointView} and
 * {@link RecommendationView} are reused verbatim here too, exactly as {@code eip-reports} itself
 * reuses them — never recomputed. Team-level only (Law 6 / NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param frictionScore the team's current computed friction score (0–100)
 * @param dominantCause the team's current largest waiting sink ({@code BLOCKED} | {@code
 *     REVIEW_WAIT} | {@code NONE})
 * @param points the team's weekly metric trend within the report's window
 * @param recommendations the team's currently fired recommendations
 */
public record ReportNarrativeTeam(
    UUID teamId,
    String teamName,
    int frictionScore,
    String dominantCause,
    List<TrendPointView> points,
    List<RecommendationView> recommendations) {}
