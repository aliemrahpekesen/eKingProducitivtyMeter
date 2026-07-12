/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import java.util.List;

/**
 * Decomposes a single work item's ordered state timeline into a {@link FlowMetrics}. The timeline
 * is the sequence of {@code (state, enteredAt)} stages — the created state first, each transition
 * next, the terminal (done) state last. A stage's duration is the gap to the next stage's entry;
 * the terminal stage has none. Time is bucketed by the stage's state: {@code IN_PROGRESS} → active,
 * {@code BLOCKED} → blocked, {@code IN_REVIEW} → review-wait; {@code TODO} counts toward cycle time
 * but neither active nor waiting. Rework is a review→in-progress bounce.
 *
 * <p>Pure and deterministic: identical stages always yield identical metrics.
 */
public final class FlowTimeline {

  static final String IN_PROGRESS = "IN_PROGRESS";
  static final String BLOCKED = "BLOCKED";
  static final String IN_REVIEW = "IN_REVIEW";

  private FlowTimeline() {}

  /**
   * Computes the decomposition for an ordered stage timeline.
   *
   * @param stages the item's stages, ordered by entry time (at least one)
   * @return the cycle-time decomposition
   * @throws IllegalArgumentException if {@code stages} is empty
   */
  public static FlowMetrics of(List<TimelineStage> stages) {
    if (stages.isEmpty()) {
      throw new IllegalArgumentException("stages must not be empty");
    }
    long active = 0;
    long blocked = 0;
    long reviewWait = 0;
    int rework = 0;
    for (int i = 0; i < stages.size() - 1; i++) {
      TimelineStage current = stages.get(i);
      TimelineStage next = stages.get(i + 1);
      long duration = Math.max(0, next.startEpochSec() - current.startEpochSec());
      switch (current.state()) {
        case IN_PROGRESS -> active += duration;
        case BLOCKED -> blocked += duration;
        case IN_REVIEW -> reviewWait += duration;
        default -> {
          // TODO / DONE / other: part of cycle time but neither active nor waiting.
        }
      }
      if (IN_REVIEW.equals(current.state()) && IN_PROGRESS.equals(next.state())) {
        rework++;
      }
    }
    long cycle =
        Math.max(0, stages.get(stages.size() - 1).startEpochSec() - stages.get(0).startEpochSec());
    return new FlowMetrics(cycle, active, blocked, reviewWait, blocked + reviewWait, rework);
  }
}
