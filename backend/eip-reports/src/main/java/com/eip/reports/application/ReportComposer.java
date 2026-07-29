/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.application;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Pure assembly of the deterministic {@code EXEC_SUMMARY} {@link ReportDocument} from the four
 * composed analytics reads (TASK-0022, ADR-023). Framework-free by design (no Spring, no JDBC) so
 * the arithmetic and assembly can be unit-tested directly, without a database. The only wall-clock
 * input is the caller-supplied {@code generatedAt} — bookkeeping only, and (with one documented
 * exception, the empty-data fallback in {@link #periodOf}) never an input to a computed value:
 * recomputing over the same {@code trends}/{@code recommendations}/{@code inFlight}/{@code summary}
 * snapshot always reproduces the same {@code totals} and {@code teams} content.
 */
final class ReportComposer {

  private ReportComposer() {}

  /**
   * Assembles the report document.
   *
   * @param weeks the requested window width, in weeks (already validated 4..52 by the caller)
   * @param generatedAt when the document is assembled (bookkeeping only)
   * @param trends the composed weekly metric trends
   * @param recommendations the composed per-team recommendations
   * @param inFlight the composed per-team in-flight work
   * @param summary the composed friction summary
   * @return the assembled document
   */
  static ReportDocument compose(
      int weeks,
      Instant generatedAt,
      TrendsView trends,
      List<TeamRecommendationsView> recommendations,
      List<TeamInFlightView> inFlight,
      FrictionSummaryView summary) {

    Period period = periodOf(weeks, generatedAt, trends);

    Map<UUID, List<RecommendationView>> recsByTeam = new HashMap<>();
    for (TeamRecommendationsView r : recommendations) {
      recsByTeam.put(r.teamId(), r.recommendations());
    }
    Map<UUID, TeamInFlightView> inFlightByTeam = new HashMap<>();
    for (TeamInFlightView i : inFlight) {
      inFlightByTeam.put(i.teamId(), i);
    }
    Map<UUID, TeamTrendView> trendsByTeam = new HashMap<>();
    for (TeamTrendView t : trends.teams()) {
      trendsByTeam.put(t.teamId(), t);
    }

    List<ReportTeamSection> teams = new ArrayList<>(summary.teams().size());
    for (TeamFrictionView tf : summary.teams()) {
      UUID key = tf.teamId();
      @Nullable TeamTrendView trend = trendsByTeam.get(key);
      List<TrendPointView> points = trend == null ? List.of() : trend.points();
      List<RecommendationView> recs = recsByTeam.getOrDefault(key, List.of());
      @Nullable TeamInFlightView flight = inFlightByTeam.get(key);
      int inFlightCount = flight == null ? 0 : flight.items().size();
      int blockedInFlightCount =
          flight == null
              ? 0
              : (int) flight.items().stream().filter(InFlightItemView::blocked).count();
      teams.add(
          new ReportTeamSection(
              tf.teamId(),
              tf.teamName(),
              tf.frictionScore(),
              tf.dominantCause(),
              points,
              recs,
              inFlightCount,
              blockedInFlightCount));
    }

    ReportTotals totals = totalsOf(trends, summary);
    String title =
        String.format(
            Locale.US,
            "Engineering Flow Report — %s..%s",
            dateOf(period.start()),
            dateOf(period.end()));

    return new ReportDocument(
        ReportDocument.CURRENT_VERSION,
        title,
        period.start(),
        period.end(),
        weeks,
        generatedAt,
        summary.metricVersion(),
        totals,
        teams);
  }

  private record Period(Instant start, Instant end) {}

  private static LocalDate dateOf(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate();
  }

  /**
   * The report window: the earliest/latest resolved week across every team's trend points. Falls
   * back to a {@code generatedAt}-anchored window when no team has resolved anything in the
   * requested window — there is no period in the data to derive from, the only case where {@code
   * generatedAt} feeds a computed value.
   */
  private static Period periodOf(int weeks, Instant generatedAt, TrendsView trends) {
    @Nullable LocalDate min = null;
    @Nullable LocalDate max = null;
    for (TeamTrendView team : trends.teams()) {
      for (TrendPointView point : team.points()) {
        LocalDate weekStart = LocalDate.parse(point.weekStart());
        if (min == null || weekStart.isBefore(min)) {
          min = weekStart;
        }
        if (max == null || weekStart.isAfter(max)) {
          max = weekStart;
        }
      }
    }
    if (min == null || max == null) {
      LocalDate end = dateOf(generatedAt);
      LocalDate start = end.minusWeeks(weeks);
      return new Period(startOfUtcDay(start), startOfUtcDay(end));
    }
    return new Period(startOfUtcDay(min), startOfUtcDay(max.plusDays(7)));
  }

  private static Instant startOfUtcDay(LocalDate date) {
    return date.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /** Totals arithmetic (formulas + a worked example: {@code docs/metrics/Reports.md}). */
  private static ReportTotals totalsOf(TrendsView trends, FrictionSummaryView summary) {
    long itemsResolved = 0;
    double avgCycleWeighted = 0;
    double p85CycleWeighted = 0;
    double flowEfficiencyWeighted = 0;
    double blockedWeighted = 0;
    double reviewWaitWeighted = 0;
    for (TeamTrendView team : trends.teams()) {
      for (TrendPointView point : team.points()) {
        int weight = point.itemsResolved();
        itemsResolved += weight;
        avgCycleWeighted += (double) point.avgCycleSec() * weight;
        p85CycleWeighted += (double) point.p85CycleSec() * weight;
        flowEfficiencyWeighted += (double) point.flowEfficiencyPct() * weight;
        blockedWeighted += (double) point.blockedPct() * weight;
        reviewWaitWeighted += (double) point.reviewWaitPct() * weight;
      }
    }
    long avgCycleSec = itemsResolved == 0 ? 0 : Math.round(avgCycleWeighted / itemsResolved);
    long p85CycleSec = itemsResolved == 0 ? 0 : Math.round(p85CycleWeighted / itemsResolved);
    int flowEfficiencyPct =
        itemsResolved == 0 ? 0 : (int) Math.round(flowEfficiencyWeighted / itemsResolved);
    int blockedPct = itemsResolved == 0 ? 0 : (int) Math.round(blockedWeighted / itemsResolved);
    int reviewWaitPct =
        itemsResolved == 0 ? 0 : (int) Math.round(reviewWaitWeighted / itemsResolved);

    List<TeamFrictionView> reportingTeams = summary.teams();
    int avgFrictionScore =
        reportingTeams.isEmpty()
            ? 0
            : (int)
                Math.round(
                    reportingTeams.stream()
                        .mapToInt(TeamFrictionView::frictionScore)
                        .average()
                        .orElse(0));

    return new ReportTotals(
        summary.teamsReporting(),
        (int) itemsResolved,
        avgCycleSec,
        p85CycleSec,
        flowEfficiencyPct,
        blockedPct,
        reviewWaitPct,
        avgFrictionScore);
  }
}
