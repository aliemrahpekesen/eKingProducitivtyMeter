/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.application;

import com.eip.analytics.api.GetMetricTrendsQuery;
import com.eip.analytics.api.TeamTrendView;
import com.eip.analytics.api.TrendPointView;
import com.eip.analytics.api.TrendsView;
import com.eip.analytics.friction.FlowMetrics;
import com.eip.analytics.friction.FrictionCalculator;
import com.eip.analytics.persistence.TrendRepository;
import com.eip.analytics.persistence.TrendRepository.TrendRow;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles the weekly metric-trends view from the correlated canonical flow. Anchored to the
 * tenant's own data — the latest resolved work item — rather than wall-clock time, so recomputing
 * over unchanged data reproduces a byte-identical response. One tenant-bound read-only transaction;
 * all aggregation (per-team, per-week grouping, percentiles, the friction composite) runs in Java
 * over the one bounded row set the repository returns. Team-level only (Law 6 / NFR-071).
 */
@Service
public class TrendService implements GetMetricTrendsQuery {

  /** Minimum accepted window width, in weeks. */
  public static final int MIN_WEEKS = 4;

  /** Maximum accepted window width, in weeks. */
  public static final int MAX_WEEKS = 52;

  private static final Comparator<TeamTrendView> TEAM_ORDER =
      Comparator.comparing(TeamTrendView::teamName).thenComparing(t -> t.teamId().toString());

  private final TenantTransactionRunner tx;
  private final TrendRepository repository;

  /**
   * Creates the service.
   *
   * @param tx the tenant-bound transaction runner
   * @param repository the trends read repository
   */
  public TrendService(TenantTransactionRunner tx, TrendRepository repository) {
    this.tx = tx;
    this.repository = repository;
  }

  @Override
  public TrendsView trends(int weeks) {
    int clamped = Math.min(MAX_WEEKS, Math.max(MIN_WEEKS, weeks));
    return tx.readCurrent(() -> build(clamped));
  }

  private TrendsView build(int weeks) {
    Optional<Instant> anchor = repository.anchor();
    if (anchor.isEmpty()) {
      return new TrendsView(weeks, List.of());
    }
    LocalDate anchorWeekStart = weekStartOf(anchor.get());
    Instant windowStart =
        anchorWeekStart.minusWeeks(weeks - 1L).atStartOfDay(ZoneOffset.UTC).toInstant();

    List<TrendRow> rows = repository.rows(windowStart);
    Map<UUID, String> teamNames = repository.teamNames();

    Map<UUID, Map<LocalDate, List<FlowMetrics>>> byTeamWeek = new LinkedHashMap<>();
    for (TrendRow row : rows) {
      byTeamWeek
          .computeIfAbsent(row.teamId(), id -> new LinkedHashMap<>())
          .computeIfAbsent(row.weekStart(), w -> new ArrayList<>())
          .add(row.metrics());
    }

    List<TeamTrendView> teams = new ArrayList<>(byTeamWeek.size());
    byTeamWeek.forEach(
        (teamId, weekMap) -> {
          List<TrendPointView> points = new ArrayList<>(weekMap.size());
          weekMap.forEach((weekStart, metrics) -> points.add(pointOf(teamId, weekStart, metrics)));
          teams.add(
              new TeamTrendView(teamId, teamNames.getOrDefault(teamId, teamId.toString()), points));
        });
    teams.sort(TEAM_ORDER);

    return new TrendsView(weeks, teams);
  }

  private static TrendPointView pointOf(
      UUID teamId, LocalDate weekStart, List<FlowMetrics> metrics) {
    int itemsResolved = metrics.size();
    long totalCycle = 0;
    long totalActive = 0;
    long totalBlocked = 0;
    long totalReviewWait = 0;
    List<Long> cycles = new ArrayList<>(itemsResolved);
    for (FlowMetrics m : metrics) {
      totalCycle += m.cycleSec();
      totalActive += m.activeSec();
      totalBlocked += m.blockedSec();
      totalReviewWait += m.reviewWaitSec();
      cycles.add(m.cycleSec());
    }
    cycles.sort(Comparator.naturalOrder());
    int p85Index = (int) Math.ceil(0.85 * itemsResolved) - 1;
    long p85CycleSec = cycles.get(p85Index);
    long avgCycleSec = totalCycle / itemsResolved;

    int frictionScore = FrictionCalculator.of(teamId, metrics).frictionScore();

    return new TrendPointView(
        weekStart.toString(),
        itemsResolved,
        avgCycleSec,
        p85CycleSec,
        pct(totalActive, totalCycle),
        pct(totalBlocked, totalCycle),
        pct(totalReviewWait, totalCycle),
        frictionScore);
  }

  private static int pct(long part, long whole) {
    return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
  }

  /** The UTC ISO week's Monday containing the instant — matches the repository's SQL bucketing. */
  private static LocalDate weekStartOf(Instant instant) {
    return instant
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }
}
