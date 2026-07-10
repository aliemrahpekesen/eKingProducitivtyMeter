/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves the per-item cycle-time decomposition, including the rework bounce. */
class FlowTimelineTest {

  private static final long H = 3600L;

  private static TimelineStage s(String state, long hour) {
    return new TimelineStage(state, hour * H);
  }

  @Test
  void decomposes_a_blocked_and_reworked_item() {
    // PLAT-101 timeline: TODO0 IP2 BLOCKED4 IP28 IR30 IP54 IR56 DONE60.
    FlowMetrics m =
        FlowTimeline.of(
            List.of(
                s("TODO", 0),
                s("IN_PROGRESS", 2),
                s("BLOCKED", 4),
                s("IN_PROGRESS", 28),
                s("IN_REVIEW", 30),
                s("IN_PROGRESS", 54),
                s("IN_REVIEW", 56),
                s("DONE", 60)));

    assertThat(m.cycleSec()).isEqualTo(60 * H);
    assertThat(m.activeSec()).isEqualTo(6 * H); // 2 + 2 + 2
    assertThat(m.blockedSec()).isEqualTo(24 * H);
    assertThat(m.reviewWaitSec()).isEqualTo(28 * H); // 24 + 4
    assertThat(m.waitingSec()).isEqualTo(52 * H);
    assertThat(m.reworkCount()).isEqualTo(1); // IR30 -> IP54
  }

  @Test
  void a_clean_item_has_no_blocked_or_rework() {
    FlowMetrics m =
        FlowTimeline.of(
            List.of(s("TODO", 0), s("IN_PROGRESS", 1), s("IN_REVIEW", 3), s("DONE", 6)));
    assertThat(m.blockedSec()).isZero();
    assertThat(m.reworkCount()).isZero();
    assertThat(m.activeSec()).isEqualTo(2 * H);
    assertThat(m.reviewWaitSec()).isEqualTo(3 * H);
    assertThat(m.cycleSec()).isEqualTo(6 * H);
  }

  @Test
  void a_single_stage_has_zero_durations() {
    FlowMetrics m = FlowTimeline.of(List.of(s("DONE", 5)));
    assertThat(m.cycleSec()).isZero();
    assertThat(m.waitingSec()).isZero();
  }

  @Test
  void empty_timeline_is_rejected() {
    assertThatThrownBy(() -> FlowTimeline.of(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
