/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.UUID;

/**
 * Reads one team's friction drill-to-evidence (correlated work items, PRs, builds, gates, and
 * transition timelines) for the current tenant, RLS-scoped. Team-level only — evidence identifies
 * artifacts, never individuals (NFR-071).
 */
public interface GetFrictionEvidenceQuery {

  /**
   * Builds the evidence for one team.
   *
   * @param teamId the team to drill into
   * @return the team's evidence payload
   */
  FrictionEvidenceView evidence(UUID teamId);
}
