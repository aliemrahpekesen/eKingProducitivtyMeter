/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Engineering Friction rule-based recommendation engine ({@code recommendations v0.1}, see
 * {@code docs/metrics/Recommendations.md} for the versioned rule table). Pure and deterministic:
 * evaluating the same {@link TeamSignals} always yields the same recommendations in the same order.
 * Team-level only — the input carries no individual attribution (Law 6 / NFR-071).
 *
 * <p>Five rules are evaluated independently; any subset — including none — may fire. A healthy team
 * (no rule fires) yields an empty list rather than a fabricated "all clear" entry.
 */
public final class RecommendationEngine {

  private static final int REVIEW_WAIT_WARN_PCT = 40;
  private static final int REVIEW_WAIT_CRITICAL_PCT = 55;
  private static final int BLOCKED_WARN_PCT = 25;
  private static final int BLOCKED_CRITICAL_PCT = 40;
  private static final int REWORK_RATIO_NUMERATOR = 10;
  private static final int REWORK_RATIO_DENOMINATOR = 3;
  private static final int FLOW_EFFICIENCY_WARN_PCT = 30;
  private static final int FLOW_EFFICIENCY_SEVERE_PCT = 20;
  private static final int AGING_WIP_MULTIPLIER = 2;

  private static final Map<String, Integer> SEVERITY_RANK =
      Map.of("CRITICAL", 0, "WARN", 1, "INFO", 2);

  private static final Comparator<Recommendation> ORDER =
      Comparator.<Recommendation, Integer>comparing(r -> SEVERITY_RANK.get(r.severity()))
          .thenComparing(Recommendation::code);

  private RecommendationEngine() {}

  /**
   * Evaluates every rule against one team's signals.
   *
   * @param s the team's friction and in-flight signals
   * @return the fired recommendations, ordered severity {@code CRITICAL} > {@code WARN} > {@code
   *     INFO} then {@code code} ascending; empty when no rule fires
   */
  public static List<Recommendation> evaluate(TeamSignals s) {
    List<Recommendation> recommendations = new ArrayList<>();
    reviewWait(s).ifPresent(recommendations::add);
    blocked(s).ifPresent(recommendations::add);
    rework(s).ifPresent(recommendations::add);
    flowEfficiency(s).ifPresent(recommendations::add);
    agingWip(s).ifPresent(recommendations::add);
    recommendations.sort(ORDER);
    return List.copyOf(recommendations);
  }

  private static Optional<Recommendation> reviewWait(TeamSignals s) {
    if (s.reviewWaitPct() < REVIEW_WAIT_WARN_PCT) {
      return Optional.empty();
    }
    String severity = s.reviewWaitPct() >= REVIEW_WAIT_CRITICAL_PCT ? "CRITICAL" : "WARN";
    return Optional.of(
        new Recommendation(
            "R-REVIEW-WAIT",
            severity,
            "Review wait dominates cycle time",
            ("Review wait consumes %d%% of cycle time across %d work item(s), the team's largest"
                    + " waiting sink.")
                .formatted(s.reviewWaitPct(), s.workItems()),
            List.of("Add reviewer capacity", "Set review SLAs", "Reduce PR size"),
            List.of("reviewWaitPct=" + s.reviewWaitPct())));
  }

  private static Optional<Recommendation> blocked(TeamSignals s) {
    if (s.blockedPct() < BLOCKED_WARN_PCT) {
      return Optional.empty();
    }
    String severity = s.blockedPct() >= BLOCKED_CRITICAL_PCT ? "CRITICAL" : "WARN";
    return Optional.of(
        new Recommendation(
            "R-BLOCKED",
            severity,
            "Blocked time is eroding delivery",
            "Blocked time accounts for %d%% of cycle time across %d work item(s)."
                .formatted(s.blockedPct(), s.workItems()),
            List.of(
                "Run daily blocker triage",
                "Escalate external dependencies",
                "Track blocker owners at the team level"),
            List.of("blockedPct=" + s.blockedPct())));
  }

  private static Optional<Recommendation> rework(TeamSignals s) {
    boolean fires =
        s.workItems() > 0
            && s.reworkCount() * REWORK_RATIO_NUMERATOR >= s.workItems() * REWORK_RATIO_DENOMINATOR;
    if (!fires) {
      return Optional.empty();
    }
    return Optional.of(
        new Recommendation(
            "R-REWORK",
            "WARN",
            "High rework loop rate",
            ("%d review→in-progress rework bounce(s) across %d work item(s) — a rate the team"
                    + " should not sustain.")
                .formatted(s.reworkCount(), s.workItems()),
            List.of(
                "Strengthen definition-of-ready",
                "Add earlier design review",
                "Pair on ambiguous items"),
            List.of("reworkCount=" + s.reworkCount(), "workItems=" + s.workItems())));
  }

  private static Optional<Recommendation> flowEfficiency(TeamSignals s) {
    if (s.flowEfficiencyPct() >= FLOW_EFFICIENCY_WARN_PCT) {
      return Optional.empty();
    }
    String rationale =
        s.flowEfficiencyPct() < FLOW_EFFICIENCY_SEVERE_PCT
            ? "Only %d%% of cycle time is active work — severely low flow efficiency."
                .formatted(s.flowEfficiencyPct())
            : "Only %d%% of cycle time is active work.".formatted(s.flowEfficiencyPct());
    return Optional.of(
        new Recommendation(
            "R-FLOW-EFFICIENCY",
            "WARN",
            "Low flow efficiency",
            rationale,
            List.of("Lower WIP limits", "Swarm on oldest items"),
            List.of("flowEfficiencyPct=" + s.flowEfficiencyPct())));
  }

  private static Optional<Recommendation> agingWip(TeamSignals s) {
    boolean fires =
        s.avgCycleSec() > 0
            && s.inFlightCount() > 0
            && s.maxInFlightAgeSec() > (long) AGING_WIP_MULTIPLIER * s.avgCycleSec();
    if (!fires) {
      return Optional.empty();
    }
    return Optional.of(
        new Recommendation(
            "R-AGING-WIP",
            "WARN",
            "Aging work in progress",
            ("The oldest in-flight item has been open %d s, more than %dx the team's average"
                    + " cycle time (%d s).")
                .formatted(s.maxInFlightAgeSec(), AGING_WIP_MULTIPLIER, s.avgCycleSec()),
            List.of("Review oldest in-flight items", "Split or close stale work"),
            List.of(
                "maxInFlightAgeSec=" + s.maxInFlightAgeSec(), "avgCycleSec=" + s.avgCycleSec())));
  }
}
