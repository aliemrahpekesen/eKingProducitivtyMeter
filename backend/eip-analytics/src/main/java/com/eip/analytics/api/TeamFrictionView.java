/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.UUID;

/**
 * One team's computed Engineering Friction v0.1 result and its cycle-time component breakdown, read
 * from the {@code analytics.rm_team_friction_current} read model. Team-level only — no individual
 * attribution anywhere in this view (Law 6 / NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param frictionScore composite friction (0–100, EXPERIMENTAL v0.1)
 * @param dominantCause the largest waiting sink ({@code BLOCKED} | {@code REVIEW_WAIT} | {@code
 *     NONE})
 * @param workItems number of work items aggregated
 * @param totalCycleSec sum of item cycle times, in seconds
 * @param activeSec time actively in progress, in seconds
 * @param waitingSec time waiting (blocked + review), in seconds
 * @param blockedSec time blocked, in seconds
 * @param reviewWaitSec time waiting in review, in seconds
 * @param reworkCount review→in-progress bounces
 * @param flowEfficiencyPct active share of cycle time, as a whole percent
 * @param blockedPct blocked share of cycle time, as a whole percent
 * @param reviewWaitPct review-wait share of cycle time, as a whole percent
 */
public record TeamFrictionView(
    UUID teamId,
    String teamName,
    int frictionScore,
    String dominantCause,
    int workItems,
    long totalCycleSec,
    long activeSec,
    long waitingSec,
    long blockedSec,
    long reviewWaitSec,
    int reworkCount,
    int flowEfficiencyPct,
    int blockedPct,
    int reviewWaitPct) {}
