/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import java.util.UUID;

/**
 * The team-level inputs {@link RecommendationEngine} evaluates: the computed friction breakdown
 * plus the in-flight (unresolved) work signal. Team-level only — no individual attribution anywhere
 * (Law 6 / NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param frictionScore the team's current composite friction score (0–100)
 * @param dominantCause the largest waiting sink ({@code BLOCKED} | {@code REVIEW_WAIT} | {@code
 *     NONE})
 * @param workItems number of resolved work items the friction score is aggregated over
 * @param flowEfficiencyPct active share of cycle time, as a whole percent
 * @param blockedPct blocked share of cycle time, as a whole percent
 * @param reviewWaitPct review-wait share of cycle time, as a whole percent
 * @param reworkCount review→in-progress bounces across the resolved items
 * @param avgCycleSec mean create→resolve time across the resolved items, in seconds ({@code 0} when
 *     {@code workItems} is {@code 0})
 * @param inFlightCount number of currently unresolved work items
 * @param maxInFlightAgeSec the oldest unresolved item's age, in seconds ({@code 0} when {@code
 *     inFlightCount} is {@code 0})
 */
public record TeamSignals(
    UUID teamId,
    String teamName,
    int frictionScore,
    String dominantCause,
    int workItems,
    int flowEfficiencyPct,
    int blockedPct,
    int reviewWaitPct,
    int reworkCount,
    long avgCycleSec,
    int inFlightCount,
    long maxInFlightAgeSec) {}
