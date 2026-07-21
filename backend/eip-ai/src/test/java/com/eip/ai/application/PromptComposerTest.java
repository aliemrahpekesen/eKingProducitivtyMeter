/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.ai.api.ReportNarrativeInput;
import com.eip.ai.api.ReportNarrativeTeam;
import com.eip.ai.api.ReportNarrativeTotals;
import com.eip.ai.application.PromptComposer.ComposedPrompt;
import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TeamFrictionView;
import com.eip.analytics.api.TeamRecommendationsView;
import com.eip.analytics.api.TeamTrendView;
import com.eip.analytics.api.TrendPointView;
import com.eip.analytics.api.TrendsView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link PromptComposer} is deterministic, embeds only the composed deterministic numbers
 * into the user prompt, excludes the fixed system prompt from the self-derived whitelist, and never
 * embeds numbers from a team with no data.
 */
class PromptComposerTest {

  private static final UUID TEAM_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  private static final UUID TEAM_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

  @Test
  void composeExplain_is_deterministic_and_embeds_only_the_supplied_numbers() {
    FrictionSummaryView summary =
        new FrictionSummaryView(
            null,
            "engineering_friction_v0.1",
            null,
            true,
            List.of(friction(TEAM_A, "Alpha", 85, "REVIEW_WAIT")),
            1);
    TrendsView trends =
        new TrendsView(
            8,
            List.of(
                new TeamTrendView(
                    TEAM_A,
                    "Alpha",
                    List.of(point("2026-01-05", 10, 28800, 43200, 45, 18, 37, 85)))));
    List<TeamRecommendationsView> recommendations =
        List.of(
            new TeamRecommendationsView(
                TEAM_A,
                "Alpha",
                85,
                List.of(
                    new RecommendationView(
                        "R-REVIEW-WAIT",
                        "WARN",
                        "Review wait is high",
                        "reviewWaitPct=37 exceeds the 30 threshold",
                        List.of("Add reviewers"),
                        List.of("reviewWaitPct=37")))));

    ComposedPrompt first = PromptComposer.composeExplain(summary, trends, recommendations, 8);
    ComposedPrompt second = PromptComposer.composeExplain(summary, trends, recommendations, 8);

    assertThat(first.userPrompt()).isEqualTo(second.userPrompt());
    assertThat(first.systemPrompt()).isEqualTo(PromptComposer.SYSTEM_PROMPT);
    assertThat(first.userPrompt())
        .contains("Alpha")
        .contains("REVIEW_WAIT")
        .contains("R-REVIEW-WAIT");

    // Every real number offered to the LLM is whitelisted, normalized (28800s/43200s -> exactly
    // 8.0h/12.0h, which normalize further to "8"/"12" — the same 17.0 -> 17 rule
    // NumericCrossChecker
    // documents).
    assertThat(first.numericWhitelist()).contains("85", "10", "45", "18", "37", "8", "12", "30");
    // The fixed system prompt's own numbers (e.g. "250" from "max ~250 words") never leak in.
    assertThat(first.numericWhitelist()).doesNotContain("250");
  }

  @Test
  void composeExplain_omits_the_weekly_trend_section_for_a_team_with_no_points() {
    FrictionSummaryView summary =
        new FrictionSummaryView(
            null, null, null, true, List.of(friction(TEAM_B, "Beta", 20, "NONE")), 1);
    TrendsView emptyTrends = new TrendsView(8, List.of());

    ComposedPrompt composed = PromptComposer.composeExplain(summary, emptyTrends, List.of(), 8);

    assertThat(composed.userPrompt()).contains("Beta").doesNotContain("Weekly trend");
    assertThat(composed.userPrompt()).doesNotContain("Recommendations:");
  }

  @Test
  void composeNarrate_embeds_totals_and_team_sections_deterministically() {
    ReportNarrativeTotals totals = new ReportNarrativeTotals(1, 10, 28800, 43200, 45, 18, 37, 85);
    ReportNarrativeTeam team =
        new ReportNarrativeTeam(
            TEAM_A,
            "Alpha",
            85,
            "REVIEW_WAIT",
            List.of(point("2026-01-05", 10, 28800, 43200, 45, 18, 37, 85)),
            List.of());
    ReportNarrativeInput input =
        new ReportNarrativeInput(
            UUID.randomUUID(),
            "Engineering Flow Report — 2026-01-05..2026-01-19",
            Instant.parse("2026-01-05T00:00:00Z"),
            Instant.parse("2026-01-19T00:00:00Z"),
            8,
            totals,
            List.of(team));

    ComposedPrompt first = PromptComposer.composeNarrate(input);
    ComposedPrompt second = PromptComposer.composeNarrate(input);

    assertThat(first.userPrompt()).isEqualTo(second.userPrompt());
    assertThat(first.userPrompt()).contains("Engineering Flow Report").contains("Alpha");
    assertThat(first.numericWhitelist()).contains("85", "10", "45", "18", "37", "8", "12");
  }

  private static TeamFrictionView friction(UUID teamId, String name, int score, String cause) {
    return new TeamFrictionView(teamId, name, score, cause, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
}
