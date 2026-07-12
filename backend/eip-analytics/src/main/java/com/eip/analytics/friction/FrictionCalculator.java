/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import java.util.List;
import java.util.UUID;

/**
 * Aggregates a team's per-item {@link FlowMetrics} into a {@link TeamFriction} — the EXPERIMENTAL
 * Engineering Friction v0.1 composite. Pure and deterministic: the same items always yield the same
 * score, with no clock read or randomness.
 *
 * <p><b>Formula (v0.1).</b> Let {@code waitingRatio = (blocked + reviewWait) / totalCycle} be the
 * share of the team's cycle time spent waiting rather than actively working, and {@code
 * reworkPerItem = reworkCount / workItems}. Then
 *
 * <pre>{@code
 * frictionScore = round( min(100, 100 * waitingRatio + 30 * reworkPerItem) )
 * }</pre>
 *
 * i.e. friction is the percentage of cycle time lost to waiting, plus a bounded rework penalty
 * ({@value #REWORK_POINTS} points per rework-per-item). Higher is worse; it is a relative team
 * signal, not an SLA. {@code dominantCause} is the larger of the two waiting sinks.
 */
public final class FrictionCalculator {

  /** Points added to the score per rework-bounce-per-item (the v0.1 rework penalty weight). */
  public static final int REWORK_POINTS = 30;

  /** The maximum score. */
  public static final int MAX_SCORE = 100;

  private FrictionCalculator() {}

  /**
   * Aggregates one team's item metrics into its friction row.
   *
   * @param teamId the team
   * @param items the team's per-item decompositions (may be empty)
   * @return the team's aggregated friction
   */
  public static TeamFriction of(UUID teamId, List<FlowMetrics> items) {
    long totalCycle = 0;
    long active = 0;
    long blocked = 0;
    long reviewWait = 0;
    int rework = 0;
    for (FlowMetrics m : items) {
      totalCycle += m.cycleSec();
      active += m.activeSec();
      blocked += m.blockedSec();
      reviewWait += m.reviewWaitSec();
      rework += m.reworkCount();
    }
    long waiting = blocked + reviewWait;
    int workItems = items.size();

    double flowEfficiency = ratio(active, totalCycle);
    double blockedRatio = ratio(blocked, totalCycle);
    double reviewWaitRatio = ratio(reviewWait, totalCycle);
    double waitingRatio = ratio(waiting, totalCycle);
    double reworkPerItem = workItems == 0 ? 0.0 : (double) rework / workItems;

    int score =
        (int) Math.round(Math.min(MAX_SCORE, 100.0 * waitingRatio + REWORK_POINTS * reworkPerItem));

    return new TeamFriction(
        teamId,
        workItems,
        totalCycle,
        active,
        blocked,
        reviewWait,
        waiting,
        rework,
        flowEfficiency,
        blockedRatio,
        reviewWaitRatio,
        score,
        dominantCause(blocked, reviewWait));
  }

  private static double ratio(long part, long whole) {
    return whole == 0 ? 0.0 : (double) part / whole;
  }

  private static String dominantCause(long blocked, long reviewWait) {
    if (blocked == 0 && reviewWait == 0) {
      return "NONE";
    }
    return blocked > reviewWait ? "BLOCKED" : "REVIEW_WAIT";
  }
}
