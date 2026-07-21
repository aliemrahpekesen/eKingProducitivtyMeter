/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.application;

import com.eip.ai.api.ReportNarrativeInput;
import com.eip.ai.api.ReportNarrativeTeam;
import com.eip.ai.api.ReportNarrativeTotals;
import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TeamFrictionView;
import com.eip.analytics.api.TeamRecommendationsView;
import com.eip.analytics.api.TeamTrendView;
import com.eip.analytics.api.TrendPointView;
import com.eip.analytics.api.TrendsView;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Pure composition of the system/user prompts sent to the LLM (ADR-024). Framework-free by design
 * (no Spring) so prompt shape and the self-derived numeric whitelist can be unit-tested directly.
 * Both {@link #composeExplain} and {@link #composeNarrate} build the user prompt from the SAME
 * deterministic reads the dashboard/report shows — never a value computed here — and derive the
 * cross-check whitelist by running {@link NumericCrossChecker#extractNormalized} over the user
 * prompt text itself, so the whitelist is, by construction, exactly "every numeric token emitted
 * into the prompt". The system prompt's own fixed guardrail text is deliberately excluded from that
 * extraction (it is a constant, not tenant data, and must never itself become a citable "number").
 */
final class PromptComposer {

  /**
   * The fixed guardrail system prompt (ADR-024, verbatim): explain only, never invent, never
   * individual-level, bounded length.
   */
  static final String SYSTEM_PROMPT =
      "You explain ALREADY-COMPUTED team-level engineering flow metrics. Use ONLY the numbers"
          + " provided. Never invent numbers. Never mention, rank, or infer anything about"
          + " individual people. Answer in concise en-US prose, max ~250 words.";

  private PromptComposer() {}

  /**
   * Composes the prompt for {@code ExplainInsightsUseCase.explain(weeks)}.
   *
   * @param summary the composed friction summary
   * @param trends the composed weekly metric trends
   * @param recommendations the composed per-team recommendations
   * @param weeks the requested window width, in weeks
   * @return the composed prompt
   */
  static ComposedPrompt composeExplain(
      FrictionSummaryView summary,
      TrendsView trends,
      List<TeamRecommendationsView> recommendations,
      int weeks) {
    Map<UUID, TeamTrendView> trendsByTeam = new HashMap<>();
    for (TeamTrendView t : trends.teams()) {
      trendsByTeam.put(t.teamId(), t);
    }
    Map<UUID, List<RecommendationView>> recsByTeam = new HashMap<>();
    for (TeamRecommendationsView r : recommendations) {
      recsByTeam.put(r.teamId(), r.recommendations());
    }

    StringBuilder out = new StringBuilder();
    out.append("Engineering flow metrics for the last ")
        .append(weeks)
        .append(" week(s), ")
        .append(summary.teams().size())
        .append(" team(s) reporting, worst-first by friction score.\n\n");
    for (TeamFrictionView team : summary.teams()) {
      out.append("Team: ").append(team.teamName()).append('\n');
      out.append("  Friction score: ").append(team.frictionScore()).append('\n');
      out.append("  Dominant cause: ").append(team.dominantCause()).append('\n');
      @Nullable TeamTrendView trend = trendsByTeam.get(team.teamId());
      appendWeeklyTrend(out, trend == null ? List.of() : trend.points());
      appendRecommendations(out, recsByTeam.getOrDefault(team.teamId(), List.of()));
      out.append('\n');
    }
    String userPrompt = out.toString();
    return new ComposedPrompt(
        SYSTEM_PROMPT, userPrompt, NumericCrossChecker.extractNormalized(userPrompt));
  }

  /**
   * Composes the prompt for {@code NarrateReportUseCase.narrate(input)}.
   *
   * @param input the report's already-generated content
   * @return the composed prompt
   */
  static ComposedPrompt composeNarrate(ReportNarrativeInput input) {
    ReportNarrativeTotals totals = input.totals();
    StringBuilder out = new StringBuilder();
    out.append("Report: \"")
        .append(input.title())
        .append("\", ")
        .append(input.periodStart())
        .append(" to ")
        .append(input.periodEnd())
        .append(", ")
        .append(input.weeks())
        .append(" week(s).\n\n");
    out.append("Totals:\n");
    out.append("  Teams reporting: ").append(totals.teamsReporting()).append('\n');
    out.append("  Items resolved: ").append(totals.itemsResolved()).append('\n');
    out.append("  Avg cycle time: ").append(hours(totals.avgCycleSec())).append("h\n");
    out.append("  P85 cycle time: ").append(hours(totals.p85CycleSec())).append("h\n");
    out.append("  Flow efficiency: ").append(totals.flowEfficiencyPct()).append("%\n");
    out.append("  Blocked: ").append(totals.blockedPct()).append("%\n");
    out.append("  Review wait: ").append(totals.reviewWaitPct()).append("%\n");
    out.append("  Avg friction score: ").append(totals.avgFrictionScore()).append("\n\n");

    for (ReportNarrativeTeam team : input.teams()) {
      out.append("Team: ").append(team.teamName()).append('\n');
      out.append("  Friction score: ").append(team.frictionScore()).append('\n');
      out.append("  Dominant cause: ").append(team.dominantCause()).append('\n');
      appendWeeklyTrend(out, team.points());
      appendRecommendations(out, team.recommendations());
      out.append('\n');
    }
    String userPrompt = out.toString();
    return new ComposedPrompt(
        SYSTEM_PROMPT, userPrompt, NumericCrossChecker.extractNormalized(userPrompt));
  }

  private static void appendWeeklyTrend(StringBuilder out, List<TrendPointView> points) {
    if (points.isEmpty()) {
      return;
    }
    out.append("  Weekly trend:\n");
    for (TrendPointView p : points) {
      out.append("    ")
          .append(p.weekStart())
          .append(": itemsResolved=")
          .append(p.itemsResolved())
          .append(", avgCycle=")
          .append(hours(p.avgCycleSec()))
          .append('h')
          .append(", p85Cycle=")
          .append(hours(p.p85CycleSec()))
          .append('h')
          .append(", flowEfficiency=")
          .append(p.flowEfficiencyPct())
          .append('%')
          .append(", blocked=")
          .append(p.blockedPct())
          .append('%')
          .append(", reviewWait=")
          .append(p.reviewWaitPct())
          .append('%')
          .append(", weekFrictionScore=")
          .append(p.frictionScore())
          .append('\n');
    }
  }

  private static void appendRecommendations(StringBuilder out, List<RecommendationView> recs) {
    if (recs.isEmpty()) {
      return;
    }
    out.append("  Recommendations:\n");
    for (RecommendationView r : recs) {
      out.append("    ")
          .append(r.code())
          .append(" (")
          .append(r.severity())
          .append("): ")
          .append(r.rationale())
          .append('\n');
    }
  }

  private static String hours(long seconds) {
    return String.format(Locale.US, "%.1f", seconds / 3600.0);
  }

  /**
   * The composed system + user prompt, plus the numeric whitelist self-derived from the user
   * prompt.
   *
   * @param systemPrompt the fixed guardrail prompt
   * @param userPrompt the composed data payload
   * @param numericWhitelist every normalized numeric token in {@code userPrompt}
   */
  record ComposedPrompt(String systemPrompt, String userPrompt, Set<String> numericWhitelist) {}
}
