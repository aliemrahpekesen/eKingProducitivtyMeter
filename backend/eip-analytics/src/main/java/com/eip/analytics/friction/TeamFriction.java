/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import java.util.UUID;

/**
 * A team's aggregated friction, the row persisted to {@code analytics.rm_team_friction_current}.
 * Component seconds are team sums; ratios are shares of total cycle time; {@code frictionScore} is
 * the EXPERIMENTAL v0.1 composite (0..100); {@code dominantCause} names the largest waiting sink.
 *
 * @param teamId the team
 * @param workItems number of work items aggregated
 * @param totalCycleSec sum of item cycle times
 * @param activeSec sum of active time
 * @param blockedSec sum of blocked time
 * @param reviewWaitSec sum of review-wait time
 * @param waitingSec {@code blockedSec + reviewWaitSec}
 * @param reworkCount sum of review→in-progress bounces
 * @param flowEfficiency {@code activeSec / totalCycleSec}
 * @param blockedRatio {@code blockedSec / totalCycleSec}
 * @param reviewWaitRatio {@code reviewWaitSec / totalCycleSec}
 * @param frictionScore composite friction (0..100)
 * @param dominantCause {@code BLOCKED} | {@code REVIEW_WAIT} | {@code NONE}
 */
public record TeamFriction(
    UUID teamId,
    int workItems,
    long totalCycleSec,
    long activeSec,
    long blockedSec,
    long reviewWaitSec,
    long waitingSec,
    int reworkCount,
    double flowEfficiency,
    double blockedRatio,
    double reviewWaitRatio,
    int frictionScore,
    String dominantCause) {}
