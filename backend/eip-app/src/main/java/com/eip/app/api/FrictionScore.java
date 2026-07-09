/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

/**
 * The deterministic Engineering Friction score: a pure, documented weighted index (0–100) over the
 * team flow signals. Identical inputs always produce identical output — no AI, no randomness, no
 * clock. The weighting below is the human-readable formula mirrored in {@code
 * metric_definition.formula}, so the registry and the code never disagree.
 *
 * <p>This is a v1 placeholder over the {@code rm_team_flow_current} read model. The full Friction
 * Index (wait-time decomposition across the correlated flow graph, projected to {@code
 * metric_fact}) is the {@code eip-analytics} deterministic engine's job (DEBT-013).
 */
final class FrictionScore {

  /** Each WIP-limit breach is a strong friction signal. */
  static final int WIP_LIMIT_BREACH_WEIGHT = 10;

  /** Each item waiting in review adds friction (review wait is the classic bottleneck). */
  static final int REVIEW_QUEUE_WEIGHT = 6;

  /** Each day the oldest in-progress item has aged adds friction. */
  static final int AGE_DAY_WEIGHT = 4;

  private static final double SECONDS_PER_DAY = 86_400.0;
  static final int MAX_SCORE = 100;

  private FrictionScore() {}

  /**
   * Computes the friction score for one team.
   *
   * @param wipLimitBreaches count of WIP-limit breaches
   * @param oldestInProgressAgeSec age of the oldest in-progress item, in seconds
   * @param reviewQueueDepth items waiting in review
   * @return a deterministic 0–{@value #MAX_SCORE} friction index
   */
  static int of(int wipLimitBreaches, long oldestInProgressAgeSec, int reviewQueueDepth) {
    double ageDays = Math.max(0L, oldestInProgressAgeSec) / SECONDS_PER_DAY;
    double raw =
        (double) WIP_LIMIT_BREACH_WEIGHT * Math.max(0, wipLimitBreaches)
            + (double) REVIEW_QUEUE_WEIGHT * Math.max(0, reviewQueueDepth)
            + AGE_DAY_WEIGHT * ageDays;
    return (int) Math.round(Math.min(MAX_SCORE, raw));
  }
}
