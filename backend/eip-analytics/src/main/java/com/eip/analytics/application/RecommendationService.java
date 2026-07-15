/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.application;

import com.eip.analytics.api.GetRecommendationsQuery;
import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TeamFrictionView;
import com.eip.analytics.api.TeamRecommendationsView;
import com.eip.analytics.friction.Recommendation;
import com.eip.analytics.friction.RecommendationEngine;
import com.eip.analytics.friction.TeamSignals;
import com.eip.analytics.persistence.FrictionReadRepository;
import com.eip.analytics.persistence.FrictionReadRepository.SummaryRow;
import com.eip.analytics.persistence.InFlightRepository;
import com.eip.analytics.persistence.InFlightRepository.TeamAggregateRow;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Composes each team's rule-based recommendations ({@code recommendations v0.1}) from the computed
 * friction read model and the in-flight work signal — both read in the same tenant-bound read-only
 * transaction, so the two signals are always a consistent snapshot. The rule evaluation itself is
 * pure ({@link RecommendationEngine}); this service only assembles its input and maps its output.
 * Team-level only (Law 6 / NFR-071).
 */
@Service
public class RecommendationService implements GetRecommendationsQuery {

  private static final Comparator<TeamRecommendationsView> TEAM_ORDER =
      Comparator.comparing(TeamRecommendationsView::teamName)
          .thenComparing(t -> t.teamId().toString());

  private final TenantTransactionRunner tx;
  private final FrictionReadRepository frictionRepository;
  private final InFlightRepository inFlightRepository;

  /**
   * Creates the service.
   *
   * @param tx the tenant-bound transaction runner
   * @param frictionRepository the computed friction read model
   * @param inFlightRepository the in-flight work read model
   */
  public RecommendationService(
      TenantTransactionRunner tx,
      FrictionReadRepository frictionRepository,
      InFlightRepository inFlightRepository) {
    this.tx = tx;
    this.frictionRepository = frictionRepository;
    this.inFlightRepository = inFlightRepository;
  }

  @Override
  public List<TeamRecommendationsView> recommendations() {
    return tx.readCurrent(this::build);
  }

  private List<TeamRecommendationsView> build() {
    List<SummaryRow> summaries = frictionRepository.summaryRows();
    Map<UUID, TeamAggregateRow> inFlightByTeam = new HashMap<>();
    for (TeamAggregateRow row : inFlightRepository.aggregates()) {
      inFlightByTeam.put(row.teamId(), row);
    }

    List<TeamRecommendationsView> teams = new ArrayList<>(summaries.size());
    for (SummaryRow summary : summaries) {
      TeamFrictionView view = summary.view();
      TeamAggregateRow inFlight = inFlightByTeam.get(view.teamId());
      int inFlightCount = inFlight == null ? 0 : inFlight.count();
      long maxInFlightAgeSec = inFlight == null ? 0 : inFlight.maxAgeSec();
      long avgCycleSec = view.workItems() == 0 ? 0 : view.totalCycleSec() / view.workItems();

      TeamSignals signals =
          new TeamSignals(
              view.teamId(),
              view.teamName(),
              view.frictionScore(),
              view.dominantCause(),
              view.workItems(),
              view.flowEfficiencyPct(),
              view.blockedPct(),
              view.reviewWaitPct(),
              view.reworkCount(),
              avgCycleSec,
              inFlightCount,
              maxInFlightAgeSec);

      List<RecommendationView> recommendations =
          RecommendationEngine.evaluate(signals).stream()
              .map(RecommendationService::toView)
              .toList();
      teams.add(
          new TeamRecommendationsView(
              view.teamId(), view.teamName(), view.frictionScore(), recommendations));
    }
    teams.sort(TEAM_ORDER);
    return teams;
  }

  private static RecommendationView toView(Recommendation r) {
    return new RecommendationView(
        r.code(), r.severity(), r.title(), r.rationale(), r.actions(), r.metricRefs());
  }
}
