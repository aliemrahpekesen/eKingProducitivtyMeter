/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.analytics.api.FrictionEvidenceView;
import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.GetFrictionEvidenceQuery;
import com.eip.analytics.api.GetFrictionSummaryQuery;
import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.rbac.Permission;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Engineering Friction read surface — the first differentiating metric ("where is engineering
 * time lost"). A pure DTO adapter (BackendPlan §2.4): both endpoints delegate to the analytics
 * module's query ports; tenancy, transactions, and SQL live behind them. Team-level only (Law 6 /
 * NFR-071); evidence identifies source artifacts, never individuals.
 */
@RestController
@RequestMapping("/api/v1")
public class FrictionController {

  private final GetFrictionSummaryQuery summary;
  private final GetFrictionEvidenceQuery evidence;

  public FrictionController(GetFrictionSummaryQuery summary, GetFrictionEvidenceQuery evidence) {
    this.summary = summary;
    this.evidence = evidence;
  }

  /**
   * Returns the current tenant's Engineering Friction summary.
   *
   * @return the metric definition + worst-first per-team computed friction breakdown
   */
  @GetMapping("/friction/summary")
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  public FrictionSummaryView summary() {
    return summary.summary();
  }

  /**
   * Returns the drill-to-evidence for one team's friction.
   *
   * @param teamId the team to drill into
   * @return the team's friction evidence
   */
  @GetMapping("/friction/teams/{teamId}/evidence")
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  public FrictionEvidenceView evidence(@PathVariable UUID teamId) {
    return evidence.evidence(teamId);
  }
}
