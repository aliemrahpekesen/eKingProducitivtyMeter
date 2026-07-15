/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.InFlightItemView;
import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TeamFrictionView;
import com.eip.analytics.api.TeamInFlightView;
import com.eip.analytics.api.TeamRecommendationsView;
import com.eip.analytics.api.TeamTrendView;
import com.eip.analytics.api.TrendPointView;
import com.eip.analytics.api.TrendsView;
import com.eip.reports.api.ReportDocument;
import com.eip.reports.api.ReportTeamSection;
import com.eip.reports.api.ReportTotals;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed 2-teams/2-weeks-each fixture proving {@link ReportComposer}'s totals arithmetic to
 * the digit, the period derivation, per-team section wiring (points/recommendations/in-flight), and
 * worst-first team ordering (mirroring the friction summary's own order).
 */
class ReportComposerTest {

  private static final UUID TEAM_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  private static final UUID TEAM_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
  private static final Instant GENERATED_AT = Instant.parse("2026-02-01T12:00:00Z");

  @Test
  void composes_deterministic_document_from_two_teams_two_weeks_each() {
    TrendsView trends =
        new TrendsView(
            8,
            List.of(
                new TeamTrendView(
                    TEAM_A,
                    "Alpha",
                    List.of(
                        point("2026-01-05", 10, 100, 200, 50, 20, 30, 60),
                        point("2026-01-12", 20, 150, 250, 40, 25, 35, 70))),
                new TeamTrendView(
                    TEAM_B,
                    "Beta",
                    List.of(
                        point("2026-01-05", 5, 300, 400, 60, 10, 10, 30),
                        point("2026-01-12", 15, 120, 180, 55, 15, 20, 35)))));

    List<TeamRecommendationsView> recommendations =
        List.of(
            new TeamRecommendationsView(
                TEAM_A,
                "Alpha",
                80,
                List.of(
                    new RecommendationView(
                        "R-TEST",
                        "WARN",
                        "Title",
                        "Rationale",
                        List.of("Action1"),
                        List.of("metric=1")))));

    List<TeamInFlightView> inFlight =
        List.of(
            new TeamInFlightView(
                TEAM_A,
                "Alpha",
                List.of(new InFlightItemView("KEY-1", "T", "IN_PROGRESS", 100L, false))),
            new TeamInFlightView(
                TEAM_B,
                "Beta",
                List.of(
                    new InFlightItemView("KEY-2", "T", "BLOCKED", 200L, true),
                    new InFlightItemView("KEY-3", "T", "IN_PROGRESS", 50L, false))));

    FrictionSummaryView summary =
        new FrictionSummaryView(
            null,
            "engineering_friction_v0.1",
            null,
            true,
            List.of(
                friction(TEAM_A, "Alpha", 80, "REVIEW_WAIT"),
                friction(TEAM_B, "Beta", 40, "BLOCKED")),
            2);

    ReportDocument document =
        ReportComposer.compose(8, GENERATED_AT, trends, recommendations, inFlight, summary);

    // Period: earliest week's Monday .. latest week's Monday + 7 days, both UTC midnight.
    assertThat(document.periodStart()).isEqualTo(Instant.parse("2026-01-05T00:00:00Z"));
    assertThat(document.periodEnd()).isEqualTo(Instant.parse("2026-01-19T00:00:00Z"));
    assertThat(document.weeks()).isEqualTo(8);
    assertThat(document.generatedAt()).isEqualTo(GENERATED_AT);
    assertThat(document.reportVersion()).isEqualTo(ReportDocument.CURRENT_VERSION);
    assertThat(document.metricVersion()).isEqualTo("engineering_friction_v0.1");
    assertThat(document.title()).isEqualTo("Engineering Flow Report — 2026-01-05..2026-01-19");

    // Totals — hand-computed (see class javadoc): itemsResolved-weighted means, simple sums, and
    // an unweighted mean of the two teams' current friction scores.
    ReportTotals totals = document.totals();
    assertThat(totals.teamsReporting()).isEqualTo(2);
    assertThat(totals.itemsResolved()).isEqualTo(50); // 10+20+5+15
    assertThat(totals.avgCycleSec()).isEqualTo(146L); // round(7300/50)
    assertThat(totals.p85CycleSec()).isEqualTo(234L); // round(11700/50)
    assertThat(totals.flowEfficiencyPct()).isEqualTo(49); // round(2425/50 = 48.5)
    assertThat(totals.blockedPct()).isEqualTo(20); // round(975/50 = 19.5)
    assertThat(totals.reviewWaitPct()).isEqualTo(27); // round(1350/50 = 27.0)
    assertThat(totals.avgFrictionScore()).isEqualTo(60); // round((80+40)/2)

    // Teams — worst-first (mirrors the friction summary's own order), each section correctly wired
    // to its own points/recommendations/in-flight counts.
    assertThat(document.teams()).hasSize(2);
    ReportTeamSection alpha = document.teams().get(0);
    assertThat(alpha.teamId()).isEqualTo(TEAM_A);
    assertThat(alpha.teamName()).isEqualTo("Alpha");
    assertThat(alpha.frictionScore()).isEqualTo(80);
    assertThat(alpha.dominantCause()).isEqualTo("REVIEW_WAIT");
    assertThat(alpha.points()).hasSize(2);
    assertThat(alpha.recommendations())
        .extracting(RecommendationView::code)
        .containsExactly("R-TEST");
    assertThat(alpha.inFlightCount()).isEqualTo(1);
    assertThat(alpha.blockedInFlightCount()).isEqualTo(0);

    ReportTeamSection beta = document.teams().get(1);
    assertThat(beta.teamId()).isEqualTo(TEAM_B);
    assertThat(beta.teamName()).isEqualTo("Beta");
    assertThat(beta.frictionScore()).isEqualTo(40);
    assertThat(beta.dominantCause()).isEqualTo("BLOCKED");
    assertThat(beta.points()).hasSize(2);
    assertThat(beta.recommendations()).isEmpty();
    assertThat(beta.inFlightCount()).isEqualTo(2);
    assertThat(beta.blockedInFlightCount()).isEqualTo(1);
  }

  @Test
  void falls_back_to_a_generated_at_anchored_window_when_no_team_has_resolved_anything() {
    TrendsView emptyTrends = new TrendsView(6, List.of());
    FrictionSummaryView emptySummary =
        new FrictionSummaryView(null, null, null, true, List.of(), 0);

    ReportDocument document =
        ReportComposer.compose(6, GENERATED_AT, emptyTrends, List.of(), List.of(), emptySummary);

    assertThat(document.periodEnd()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    assertThat(document.periodStart()).isEqualTo(Instant.parse("2025-12-21T00:00:00Z"));
    assertThat(document.totals().teamsReporting()).isEqualTo(0);
    assertThat(document.totals().itemsResolved()).isEqualTo(0);
    assertThat(document.totals().avgFrictionScore()).isEqualTo(0);
    assertThat(document.teams()).isEmpty();
  }

  private static TrendPointView point(
      String weekStart,
      int itemsResolved,
      long avgCycleSec,
      long p85CycleSec,
      int flowEfficiencyPct,
      int blockedPct,
      int reviewWaitPct,
      int frictionScore) {
    return new TrendPointView(
        weekStart,
        itemsResolved,
        avgCycleSec,
        p85CycleSec,
        flowEfficiencyPct,
        blockedPct,
        reviewWaitPct,
        frictionScore);
  }

  private static TeamFrictionView friction(
      UUID teamId, String teamName, int frictionScore, String dominantCause) {
    return new TeamFrictionView(
        teamId, teamName, frictionScore, dominantCause, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }
}
