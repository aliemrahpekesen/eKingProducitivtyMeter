/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The deterministic content {@link NarrateReportUseCase#narrate(ReportNarrativeInput)} composes a
 * narrative from — {@code eip-app} builds this from a {@code GetReportQuery} read
 * (com.eip.reports.api) before calling in.
 *
 * <p><b>Why a UUID isn't enough:</b> {@code eip-ai} cannot itself hold a {@code UUID reportId} and
 * look the report up, because that would require depending on {@code eip-reports} — and {@code
 * eip-reports}'s own {@code build.gradle.kts} already declares {@code implementation(project(
 * ":eip-ai"))} (for the future Report Composition agent invocation, BackendPlan §1). Gradle refuses
 * a circular project dependency outright, and even if it didn't, BackendPlan §1's
 * allowed-dependency table names only {@code eip-core}/{@code eip-tenancy}/{@code eip-analytics}
 * for {@code eip-ai}. So the report's already-fetched content is passed in directly instead: {@code
 * eip-app}'s {@code AiExplainController} calls {@code GetReportQuery.get(id)} first and maps the
 * result onto this record (and {@link ReportNarrativeTotals}/{@link ReportNarrativeTeam}, which
 * mirror {@code com.eip.reports.api.ReportTotals}/{@code ReportTeamSection} field-for-field) — pure
 * DTO translation in the controller, nothing recomputed (ADR-024).
 *
 * @param reportId the report's id (carried through for the audit trail only; not itself used in the
 *     composed prompt)
 * @param title the report's title
 * @param periodStart the inclusive start of the reported data window
 * @param periodEnd the exclusive end of the reported data window
 * @param weeks the report's requested window width, in weeks
 * @param totals the report's headline aggregates
 * @param teams per-team sections, worst-first by current friction score
 */
public record ReportNarrativeInput(
    UUID reportId,
    String title,
    Instant periodStart,
    Instant periodEnd,
    int weeks,
    ReportNarrativeTotals totals,
    List<ReportNarrativeTeam> teams) {}
