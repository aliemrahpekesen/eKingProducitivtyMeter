/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Engineering Friction read surface — the first differentiating metric ("where is engineering
 * time lost"). Read tenant-scoped under RLS; team-level only (Law 6 / NFR-071). Deterministic: the
 * response is a pure function of the computed friction read model + correlation evidence, with no
 * AI and no clock. Evidence identifies source artifacts (work items, PRs, builds, gates) but never
 * individuals.
 */
@RestController
@RequestMapping("/api/v1")
public class FrictionController {

  private final FrictionSummaryService friction;
  private final FrictionEvidenceService evidence;

  public FrictionController(FrictionSummaryService friction, FrictionEvidenceService evidence) {
    this.friction = friction;
    this.evidence = evidence;
  }

  /**
   * Returns the current tenant's Engineering Friction summary.
   *
   * @return the metric definition + worst-first per-team computed friction breakdown
   */
  @GetMapping("/friction/summary")
  public FrictionSummaryView summary() {
    return friction.summary();
  }

  /**
   * Returns the drill-to-evidence for one team's friction: the correlated work items, PRs, builds,
   * gates, and transition timelines behind the score.
   *
   * @param teamId the team to drill into
   * @return the team's friction evidence
   */
  @GetMapping("/friction/teams/{teamId}/evidence")
  public FrictionEvidenceView evidence(@PathVariable UUID teamId) {
    return evidence.evidence(teamId);
  }
}
