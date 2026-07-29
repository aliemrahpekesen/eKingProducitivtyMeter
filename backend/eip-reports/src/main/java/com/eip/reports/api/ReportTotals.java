/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

/**
 * The report's headline aggregates across every team section (formulas + worked examples: {@code
 * docs/metrics/Reports.md}). {@code avgCycleSec}, {@code p85CycleSec}, {@code flowEfficiencyPct},
 * {@code blockedPct}, and {@code reviewWaitPct} are weighted by each weekly point's {@code
 * itemsResolved} (a week with more resolved items counts more toward the total); {@code
 * itemsResolved} and {@code teamsReporting} are simple sums/counts; {@code avgFrictionScore} is the
 * unweighted mean of every reporting team's <em>current</em> computed friction score — a single
 * value per team, not derived from the weekly points.
 *
 * @param teamsReporting number of teams with computed friction (mirrors the friction summary's own
 *     count)
 * @param itemsResolved total items resolved across every team's weekly points in the window
 * @param avgCycleSec items-resolved-weighted mean create→resolve time, in seconds
 * @param p85CycleSec items-resolved-weighted mean of each week's p85 create→resolve time, in
 *     seconds
 * @param flowEfficiencyPct items-resolved-weighted mean active-time share, as a whole percent
 * @param blockedPct items-resolved-weighted mean blocked-time share, as a whole percent
 * @param reviewWaitPct items-resolved-weighted mean review-wait-time share, as a whole percent
 * @param avgFrictionScore unweighted mean of every reporting team's current friction score
 */
public record ReportTotals(
    int teamsReporting,
    int itemsResolved,
    long avgCycleSec,
    long p85CycleSec,
    int flowEfficiencyPct,
    int blockedPct,
    int reviewWaitPct,
    int avgFrictionScore) {}
