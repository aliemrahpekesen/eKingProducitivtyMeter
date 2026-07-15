/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the recommendations v0.1 rule set: the golden review-wait case, every rule's exact
 * threshold boundary, deterministic severity/code ordering, and that a healthy team fires nothing.
 * {@code docs/metrics/Recommendations.md} is the source of record for the thresholds asserted here.
 */
class RecommendationEngineTest {

  private static final UUID TEAM = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

  /** All dimensions at a safe, non-firing baseline; each test overrides only what it exercises. */
  private static TeamSignals signals(
      int workItems,
      int flowEfficiencyPct,
      int blockedPct,
      int reviewWaitPct,
      int reworkCount,
      long avgCycleSec,
      int inFlightCount,
      long maxInFlightAgeSec) {
    return new TeamSignals(
        TEAM,
        "Platform",
        0,
        "NONE",
        workItems,
        flowEfficiencyPct,
        blockedPct,
        reviewWaitPct,
        reworkCount,
        avgCycleSec,
        inFlightCount,
        maxInFlightAgeSec);
  }

  private static TeamSignals healthy() {
    return signals(10, 80, 0, 0, 0, 1000, 0, 0);
  }

  @Test
  void golden_review_wait_57_fires_critical_with_the_number_in_the_rationale() {
    TeamSignals s =
        new TeamSignals(TEAM, "Platform", 91, "REVIEW_WAIT", 3, 14, 24, 57, 1, 1_188_000, 0, 0);

    List<Recommendation> recs = RecommendationEngine.evaluate(s);

    assertThat(recs).extracting(Recommendation::code).contains("R-REVIEW-WAIT");
    Recommendation reviewWait =
        recs.stream().filter(r -> r.code().equals("R-REVIEW-WAIT")).findFirst().orElseThrow();
    assertThat(reviewWait.severity()).isEqualTo("CRITICAL");
    assertThat(reviewWait.rationale()).contains("57");
    assertThat(reviewWait.metricRefs()).contains("reviewWaitPct=57");
  }

  @Test
  void review_wait_boundaries_39_40_54_55() {
    assertThat(fires(withReviewWait(healthy(), 39), "R-REVIEW-WAIT")).isFalse();
    assertThat(severityOf(withReviewWait(healthy(), 40), "R-REVIEW-WAIT")).contains("WARN");
    assertThat(severityOf(withReviewWait(healthy(), 54), "R-REVIEW-WAIT")).contains("WARN");
    assertThat(severityOf(withReviewWait(healthy(), 55), "R-REVIEW-WAIT")).contains("CRITICAL");
  }

  @Test
  void blocked_boundaries_24_25_39_40() {
    assertThat(fires(withBlocked(healthy(), 24), "R-BLOCKED")).isFalse();
    assertThat(severityOf(withBlocked(healthy(), 25), "R-BLOCKED")).contains("WARN");
    assertThat(severityOf(withBlocked(healthy(), 39), "R-BLOCKED")).contains("WARN");
    assertThat(severityOf(withBlocked(healthy(), 40), "R-BLOCKED")).contains("CRITICAL");
  }

  @Test
  void rework_ratio_boundary_0_29_vs_0_30() {
    // 100 work items: 29 rework -> ratio 0.29 (no fire); 30 rework -> ratio 0.30 (fires).
    TeamSignals below = signals(100, 80, 0, 0, 29, 1000, 0, 0);
    TeamSignals at = signals(100, 80, 0, 0, 30, 1000, 0, 0);

    assertThat(fires(below, "R-REWORK")).isFalse();
    assertThat(severityOf(at, "R-REWORK")).contains("WARN");
  }

  @Test
  void flow_efficiency_boundary_29_vs_30_and_severe_below_20() {
    assertThat(severityOf(withFlowEfficiency(healthy(), 29), "R-FLOW-EFFICIENCY")).contains("WARN");
    assertThat(fires(withFlowEfficiency(healthy(), 30), "R-FLOW-EFFICIENCY")).isFalse();

    Recommendation severe = recommendation(withFlowEfficiency(healthy(), 19), "R-FLOW-EFFICIENCY");
    assertThat(severe.rationale()).contains("severely low");
    Recommendation mild = recommendation(withFlowEfficiency(healthy(), 25), "R-FLOW-EFFICIENCY");
    assertThat(mild.rationale()).doesNotContain("severely low");
  }

  @Test
  void aging_wip_boundary_1_9x_vs_2_1x() {
    long avgCycleSec = 10_000L;
    TeamSignals under = signals(10, 80, 0, 0, 0, avgCycleSec, 1, (long) (1.9 * avgCycleSec));
    TeamSignals over = signals(10, 80, 0, 0, 0, avgCycleSec, 1, (long) (2.1 * avgCycleSec));

    assertThat(fires(under, "R-AGING-WIP")).isFalse();
    assertThat(severityOf(over, "R-AGING-WIP")).contains("WARN");
  }

  @Test
  void aging_wip_requires_in_flight_items_and_a_positive_average_cycle() {
    TeamSignals noInFlight = signals(10, 80, 0, 0, 0, 10_000, 0, 999_999);
    TeamSignals noCycleHistory = signals(0, 80, 0, 0, 0, 0, 1, 999_999);

    assertThat(fires(noInFlight, "R-AGING-WIP")).isFalse();
    assertThat(fires(noCycleHistory, "R-AGING-WIP")).isFalse();
  }

  @Test
  void healthy_team_fires_nothing() {
    assertThat(RecommendationEngine.evaluate(healthy())).isEmpty();
  }

  @Test
  void fired_recommendations_are_ordered_critical_first_then_warn_by_code_ascending() {
    // reviewWaitPct=60 -> CRITICAL; blocked=30, floweff=10 (severe), rework ratio 0.5, aging 3x ->
    // all WARN, expected in code order: R-AGING-WIP, R-BLOCKED, R-FLOW-EFFICIENCY, R-REWORK.
    TeamSignals s = signals(10, 10, 30, 60, 5, 10_000, 1, 40_000);

    List<Recommendation> recs = RecommendationEngine.evaluate(s);

    assertThat(recs)
        .extracting(Recommendation::code)
        .containsExactly(
            "R-REVIEW-WAIT", "R-AGING-WIP", "R-BLOCKED", "R-FLOW-EFFICIENCY", "R-REWORK");
    assertThat(recs.get(0).severity()).isEqualTo("CRITICAL");
    assertThat(recs.subList(1, recs.size()))
        .extracting(Recommendation::severity)
        .containsOnly("WARN");
  }

  // --- small helpers over the immutable record ---------------------------------------------

  private static TeamSignals withReviewWait(TeamSignals s, int reviewWaitPct) {
    return signals(
        s.workItems(),
        s.flowEfficiencyPct(),
        s.blockedPct(),
        reviewWaitPct,
        s.reworkCount(),
        s.avgCycleSec(),
        s.inFlightCount(),
        s.maxInFlightAgeSec());
  }

  private static TeamSignals withBlocked(TeamSignals s, int blockedPct) {
    return signals(
        s.workItems(),
        s.flowEfficiencyPct(),
        blockedPct,
        s.reviewWaitPct(),
        s.reworkCount(),
        s.avgCycleSec(),
        s.inFlightCount(),
        s.maxInFlightAgeSec());
  }

  private static TeamSignals withFlowEfficiency(TeamSignals s, int flowEfficiencyPct) {
    return signals(
        s.workItems(),
        flowEfficiencyPct,
        s.blockedPct(),
        s.reviewWaitPct(),
        s.reworkCount(),
        s.avgCycleSec(),
        s.inFlightCount(),
        s.maxInFlightAgeSec());
  }

  private static boolean fires(TeamSignals s, String code) {
    return RecommendationEngine.evaluate(s).stream().anyMatch(r -> r.code().equals(code));
  }

  private static Optional<String> severityOf(TeamSignals s, String code) {
    return RecommendationEngine.evaluate(s).stream()
        .filter(r -> r.code().equals(code))
        .map(Recommendation::severity)
        .findFirst();
  }

  private static Recommendation recommendation(TeamSignals s, String code) {
    return RecommendationEngine.evaluate(s).stream()
        .filter(r -> r.code().equals(code))
        .findFirst()
        .orElseThrow();
  }
}
