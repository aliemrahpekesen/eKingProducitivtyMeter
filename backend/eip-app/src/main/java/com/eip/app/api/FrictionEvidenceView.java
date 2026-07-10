/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Drill-to-evidence for one team's computed friction: the correlated source artifacts behind the
 * score — each work item with its cycle-time decomposition, its stitched pull request / build /
 * quality gate, and its state-transition timeline. Team-level only; identifies <em>artifacts</em>
 * (work item keys, PR keys, …) but never individuals (NFR-071). Read tenant-scoped under RLS.
 *
 * @param teamId the team
 * @param teamName the team's display name, or {@code null} if not visible
 * @param metricVersion the friction metric version the evidence supports
 * @param items the team's work items with their evidence, ordered by key
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrictionEvidenceView(
    UUID teamId,
    @Nullable String teamName,
    String metricVersion,
    List<WorkItemEvidenceView> items) {

  /**
   * One work item's correlation evidence and flow decomposition.
   *
   * @param workItemKey the source work-item key (e.g. {@code PLAT-101})
   * @param title the work-item title
   * @param type the work-item type
   * @param status the work-item status
   * @param cycleTimeSec create→resolve time, in seconds
   * @param activeSec active time, in seconds
   * @param blockedSec blocked time, in seconds
   * @param reviewWaitSec review-wait time, in seconds
   * @param waitingSec total waiting time, in seconds
   * @param reworkCount review→in-progress bounces
   * @param pullRequestKey the correlated PR key, or {@code null}
   * @param buildKey the correlated build key, or {@code null}
   * @param buildStatus the correlated build status, or {@code null}
   * @param qualityGateKey the correlated quality-gate key, or {@code null}
   * @param qualityGateStatus the correlated quality-gate status, or {@code null}
   * @param transitions the ordered state-transition timeline
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WorkItemEvidenceView(
      @Nullable String workItemKey,
      String title,
      String type,
      String status,
      long cycleTimeSec,
      long activeSec,
      long blockedSec,
      long reviewWaitSec,
      long waitingSec,
      int reworkCount,
      @Nullable String pullRequestKey,
      @Nullable String buildKey,
      @Nullable String buildStatus,
      @Nullable String qualityGateKey,
      @Nullable String qualityGateStatus,
      List<TransitionEvidenceView> transitions) {}

  /**
   * One state transition in a work item's timeline.
   *
   * @param seq 1-based order within the item
   * @param fromState the state left, or {@code null} for the first transition
   * @param toState the state entered
   * @param atEpochSec when it occurred, in epoch seconds
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TransitionEvidenceView(
      int seq, @Nullable String fromState, String toState, long atEpochSec) {}
}
