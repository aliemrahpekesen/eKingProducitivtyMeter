/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

/**
 * One team's computed metrics for a single ISO week, aggregated from resolved work items only.
 * Deterministic: derived entirely from stored {@code resolved_at} timestamps and correlated flow
 * data, with no wall-clock read. Team-level only (Law 6 / NFR-071).
 *
 * @param weekStart the ISO date of the week's Monday (e.g. {@code "2026-01-05"})
 * @param itemsResolved number of work items resolved in this week
 * @param avgCycleSec mean create→resolve time across the week's items, in seconds
 * @param p85CycleSec the 85th-percentile create→resolve time, in seconds
 * @param flowEfficiencyPct active share of cycle time, as a whole percent
 * @param blockedPct blocked share of cycle time, as a whole percent
 * @param reviewWaitPct review-wait share of cycle time, as a whole percent
 * @param frictionScore the week's composite Engineering Friction score (0–100)
 */
public record TrendPointView(
    String weekStart,
    int itemsResolved,
    long avgCycleSec,
    long p85CycleSec,
    int flowEfficiencyPct,
    int blockedPct,
    int reviewWaitPct,
    int frictionScore) {}
