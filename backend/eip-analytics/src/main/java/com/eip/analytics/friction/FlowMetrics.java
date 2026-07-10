/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

/**
 * One work item's cycle-time decomposition (all in seconds), the per-item unit the team metric
 * aggregates. {@code cycleSec} is wall-clock create→resolve; {@code activeSec} is time in progress;
 * {@code blockedSec} and {@code reviewWaitSec} are the two waiting sinks; {@code reworkCount} is
 * the number of review→in-progress bounces.
 *
 * @param cycleSec total create→resolve time
 * @param activeSec time actively in progress
 * @param blockedSec time blocked
 * @param reviewWaitSec time waiting in review
 * @param waitingSec {@code blockedSec + reviewWaitSec}
 * @param reworkCount review→in-progress bounces
 */
public record FlowMetrics(
    long cycleSec,
    long activeSec,
    long blockedSec,
    long reviewWaitSec,
    long waitingSec,
    int reworkCount) {}
