/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves the deterministic v0.1 composite, its dominant-cause logic, and monotonic responses. */
class FrictionCalculatorTest {

  private static final long H = 3600L;
  private static final UUID TEAM = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

  private static FlowMetrics item(
      long cycleH, long activeH, long blockedH, long reviewH, int rework) {
    return new FlowMetrics(
        cycleH * H, activeH * H, blockedH * H, reviewH * H, (blockedH + reviewH) * H, rework);
  }

  @Test
  void aggregates_the_platform_team_to_a_deterministic_score() {
    // The three Platform items from the simulation dataset.
    TeamFriction f =
        FrictionCalculator.of(
            TEAM,
            List.of(
                item(60, 6, 24, 28, 1), // PLAT-101
                item(29, 4, 0, 24, 0), // PLAT-102
                item(10, 4, 0, 4, 0))); // PLAT-103

    assertThat(f.workItems()).isEqualTo(3);
    assertThat(f.totalCycleSec()).isEqualTo(99 * H);
    assertThat(f.blockedSec()).isEqualTo(24 * H);
    assertThat(f.reviewWaitSec()).isEqualTo(56 * H);
    assertThat(f.waitingSec()).isEqualTo(80 * H);
    assertThat(f.reworkCount()).isEqualTo(1);
    // 100 * 80/99 + 30 * 1/3 = 80.808 + 10 = 90.808 -> 91
    assertThat(f.frictionScore()).isEqualTo(91);
    // review-wait (56h) exceeds blocked (24h).
    assertThat(f.dominantCause()).isEqualTo("REVIEW_WAIT");
    assertThat(f.flowEfficiency())
        .isCloseTo(14.0 / 99.0, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  void friction_rises_monotonically_with_blocked_and_review_wait() {
    int low = FrictionCalculator.of(TEAM, List.of(item(100, 90, 10, 0, 0))).frictionScore();
    int moreBlocked = FrictionCalculator.of(TEAM, List.of(item(100, 70, 30, 0, 0))).frictionScore();
    int moreReview = FrictionCalculator.of(TEAM, List.of(item(100, 70, 0, 30, 0))).frictionScore();
    assertThat(low).isEqualTo(10);
    assertThat(moreBlocked).isEqualTo(30).isGreaterThan(low);
    assertThat(moreReview).isEqualTo(30).isGreaterThan(low);
  }

  @Test
  void rework_adds_a_bounded_penalty() {
    int noRework = FrictionCalculator.of(TEAM, List.of(item(100, 60, 20, 20, 0))).frictionScore();
    int withRework = FrictionCalculator.of(TEAM, List.of(item(100, 60, 20, 20, 1))).frictionScore();
    assertThat(withRework).isGreaterThan(noRework); // 40 -> 70 (40 + 30*1)
    assertThat(withRework).isEqualTo(70);
  }

  @Test
  void blocked_dominates_when_it_exceeds_review_wait() {
    TeamFriction f = FrictionCalculator.of(TEAM, List.of(item(100, 40, 40, 20, 0)));
    assertThat(f.dominantCause()).isEqualTo("BLOCKED");
  }

  @Test
  void an_empty_team_is_zero_friction_with_no_dominant_cause() {
    TeamFriction f = FrictionCalculator.of(TEAM, List.of());
    assertThat(f.frictionScore()).isZero();
    assertThat(f.workItems()).isZero();
    assertThat(f.dominantCause()).isEqualTo("NONE");
    assertThat(f.flowEfficiency()).isZero();
  }

  @Test
  void score_is_capped_at_100() {
    TeamFriction f = FrictionCalculator.of(TEAM, List.of(item(100, 0, 60, 40, 5)));
    assertThat(f.frictionScore()).isEqualTo(FrictionCalculator.MAX_SCORE);
  }
}
