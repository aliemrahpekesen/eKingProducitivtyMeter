/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.analytics.api.FrictionEvidenceView;
import com.eip.analytics.api.GetFrictionEvidenceQuery;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Resource-level team-scope enforcement (SecurityModel §4 layer 2 — its own illustrative example:
 * "an ENGINEERING_MANAGER on Team A cannot read Team B's delivery-risk detail") over {@link
 * GetFrictionEvidenceQuery}. Unlike the list-shaped views, a single-team drill-down cannot be
 * silently filtered — an out-of-scope {@code teamId} is refused with a 403 (mapped by {@code
 * ApiExceptionHandler} from {@link com.eip.core.error.PermissionDeniedException}), not a 404: the
 * caller is authenticated and the team exists, they simply may not read it, and RFC 7807-style
 * honesty about WHY beats a misleadingly-generic "not found" for a resource whose existence
 * elsewhere in the same tenant's own dashboards the caller already knows about (SecurityModel §4).
 *
 * <p>See {@link TeamScopedFrictionSummaryQuery} for the {@link Qualifier}/{@link Primary} decorator
 * mechanics this mirrors.
 */
@Service
@Primary
public class TeamScopedFrictionEvidenceQuery implements GetFrictionEvidenceQuery {

  private final GetFrictionEvidenceQuery delegate;

  /**
   * Creates the decorator.
   *
   * @param delegate the real {@code FrictionEvidenceService} bean, qualified by name
   */
  public TeamScopedFrictionEvidenceQuery(
      @Qualifier("frictionEvidenceService") GetFrictionEvidenceQuery delegate) {
    this.delegate = delegate;
  }

  @Override
  public FrictionEvidenceView evidence(UUID teamId) {
    TeamScopeFilter.requireInScope(teamId);
    return delegate.evidence(teamId);
  }
}
