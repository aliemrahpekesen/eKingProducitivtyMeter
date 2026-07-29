/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

/**
 * A report's headline aggregates, as fed to {@link NarrateReportUseCase} (ADR-024). Field-for-field
 * mirror of {@code com.eip.reports.api.ReportTotals} — {@code eip-ai} cannot depend on {@code
 * eip-reports} ({@link ReportNarrativeInput}'s javadoc explains why), so {@code eip-app} copies the
 * already-computed values across this boundary; nothing here is recomputed.
 *
 * @param teamsReporting number of teams with computed friction
 * @param itemsResolved total items resolved across every team's weekly points in the window
 * @param avgCycleSec items-resolved-weighted mean create→resolve time, in seconds
 * @param p85CycleSec items-resolved-weighted mean of each week's p85 create→resolve time, in
 *     seconds
 * @param flowEfficiencyPct items-resolved-weighted mean active-time share, as a whole percent
 * @param blockedPct items-resolved-weighted mean blocked-time share, as a whole percent
 * @param reviewWaitPct items-resolved-weighted mean review-wait-time share, as a whole percent
 * @param avgFrictionScore unweighted mean of every reporting team's current friction score
 */
public record ReportNarrativeTotals(
    int teamsReporting,
    int itemsResolved,
    long avgCycleSec,
    long p85CycleSec,
    int flowEfficiencyPct,
    int blockedPct,
    int reviewWaitPct,
    int avgFrictionScore) {}
