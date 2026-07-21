/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.ai.api.ExplainInsightsUseCase;
import com.eip.ai.api.ExplanationView;
import com.eip.ai.api.NarrateReportUseCase;
import com.eip.ai.api.ReportNarrativeInput;
import com.eip.ai.api.ReportNarrativeTeam;
import com.eip.ai.api.ReportNarrativeTotals;
import com.eip.app.security.RequiresPermission;
import com.eip.reports.api.GetReportQuery;
import com.eip.reports.api.ReportDocument;
import com.eip.reports.api.ReportDocumentView;
import com.eip.reports.api.ReportTeamSection;
import com.eip.reports.api.ReportTotals;
import com.eip.tenancy.rbac.Permission;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The opt-in AI explanation/narrative surface (ADR-024). A pure DTO adapter (BackendPlan §2.4):
 * {@code /insights/explain} delegates straight to {@code eip-ai}'s {@link ExplainInsightsUseCase};
 * {@code /reports/{id}/narrative} first reads the report via {@code eip-reports}' {@link
 * GetReportQuery} and maps its already-generated content onto {@code eip-ai}'s own {@link
 * ReportNarrativeInput} DTO before calling {@link NarrateReportUseCase} — {@code eip-ai} cannot
 * depend on {@code eip-reports} directly (see {@link ReportNarrativeInput}'s javadoc), so this
 * mapping step is the composition point, exactly the kind of cross-module DTO translation {@code
 * ReportsController} itself already does composing three {@code eip-reports} ports. Both actions
 * are stateless beyond the hash-only {@code ai.llm_call_audit} row {@code eip-ai} writes — nothing
 * new is created for a client to {@code GET} later, so both return 200.
 */
@RestController
@RequestMapping("/api/v1")
public class AiExplainController {

  private final ExplainInsightsUseCase explain;
  private final NarrateReportUseCase narrate;
  private final GetReportQuery report;

  /**
   * Creates the controller.
   *
   * @param explain the dashboard-explanation port
   * @param narrate the report-narration port
   * @param report the report-detail query port (source of the narrated content)
   */
  public AiExplainController(
      ExplainInsightsUseCase explain, NarrateReportUseCase narrate, GetReportQuery report) {
    this.explain = explain;
    this.narrate = narrate;
    this.report = report;
  }

  /**
   * Generates an AI explanation of the current tenant's dashboard metrics.
   *
   * @param request the explanation request
   * @return the generated explanation
   */
  @PostMapping("/insights/explain")
  @RequiresPermission(Permission.AI_AGENT_INVOKE)
  public ExplanationView explain(@RequestBody ExplainRequest request) {
    return explain.explain(request.weeks());
  }

  /**
   * Generates an AI narrative of one already-generated report.
   *
   * @param id the report id
   * @return the generated narrative
   */
  @PostMapping("/reports/{id}/narrative")
  @RequiresPermission(Permission.AI_AGENT_INVOKE)
  public ExplanationView narrative(@PathVariable UUID id) {
    return narrate.narrate(toNarrativeInput(report.get(id)));
  }

  private static ReportNarrativeInput toNarrativeInput(ReportDocumentView view) {
    ReportDocument document = view.document();
    ReportTotals totals = document.totals();
    return new ReportNarrativeInput(
        view.report().id(),
        document.title(),
        document.periodStart(),
        document.periodEnd(),
        document.weeks(),
        new ReportNarrativeTotals(
            totals.teamsReporting(),
            totals.itemsResolved(),
            totals.avgCycleSec(),
            totals.p85CycleSec(),
            totals.flowEfficiencyPct(),
            totals.blockedPct(),
            totals.reviewWaitPct(),
            totals.avgFrictionScore()),
        document.teams().stream().map(AiExplainController::toNarrativeTeam).toList());
  }

  private static ReportNarrativeTeam toNarrativeTeam(ReportTeamSection team) {
    return new ReportNarrativeTeam(
        team.teamId(),
        team.teamName(),
        team.frictionScore(),
        team.dominantCause(),
        team.points(),
        team.recommendations());
  }

  /**
   * Dashboard-explanation request.
   *
   * @param weeks the requested trend window width, in weeks (must be in {@code 4..52})
   */
  public record ExplainRequest(int weeks) {}
}
