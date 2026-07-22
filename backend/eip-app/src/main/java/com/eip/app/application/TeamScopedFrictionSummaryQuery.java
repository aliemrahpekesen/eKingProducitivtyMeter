/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.GetFrictionSummaryQuery;
import com.eip.analytics.api.TeamFrictionView;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Resource-level team-scope enforcement (SecurityModel §4 layer 2, DEBT-012 residual, part 2) over
 * {@link GetFrictionSummaryQuery}: decorates {@code eip-analytics}' {@code FrictionSummaryService}
 * bean — resolved by name via {@link Qualifier} rather than by importing its concrete class, so
 * this class stays within {@code eip-app}'s ArchUnit boundary ({@code
 * ArchitectureRulesTest#eip_app_owns_no_pipeline_business_logic} forbids depending on {@code
 * com.eip.analytics.application..}; the query-port interfaces in {@code com.eip.analytics.api..}
 * remain the only allowed surface, exactly as every controller already uses) — and marked {@link
 * Primary} so every existing caller (constructor-injecting the plain {@link
 * GetFrictionSummaryQuery} interface, e.g. {@code FrictionController}) receives this decorated bean
 * with NO changes to that caller. Filters {@link FrictionSummaryView#teams()} to the caller's
 * {@link com.eip.app.security.EipPrincipal#scopedTeamIds()} BEFORE the response is returned, and
 * corrects the derived {@code teamsReporting} count so it never leaks the true cross-team total to
 * a scoped caller — an unrestricted caller (empty scope, the default for every role today) gets the
 * identical unfiltered response as before this change.
 */
@Service
@Primary
public class TeamScopedFrictionSummaryQuery implements GetFrictionSummaryQuery {

  private final GetFrictionSummaryQuery delegate;

  /**
   * Creates the decorator.
   *
   * @param delegate the real {@code FrictionSummaryService} bean, qualified by name
   */
  public TeamScopedFrictionSummaryQuery(
      @Qualifier("frictionSummaryService") GetFrictionSummaryQuery delegate) {
    this.delegate = delegate;
  }

  @Override
  public FrictionSummaryView summary() {
    FrictionSummaryView full = delegate.summary();
    List<TeamFrictionView> teams =
        TeamScopeFilter.restrictedTo(full.teams(), TeamFrictionView::teamId);
    if (teams == full.teams()) {
      return full;
    }
    return new FrictionSummaryView(
        full.metric(),
        full.metricVersion(),
        full.computedAt(),
        full.simulation(),
        teams,
        teams.size());
  }
}
