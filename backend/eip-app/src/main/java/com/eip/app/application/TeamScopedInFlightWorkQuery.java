/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.analytics.api.GetInFlightWorkQuery;
import com.eip.analytics.api.TeamInFlightView;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Resource-level team-scope enforcement (SecurityModel §4 layer 2, DEBT-012 residual, part 2) over
 * {@link GetInFlightWorkQuery}. See {@link TeamScopedFrictionSummaryQuery} for the {@link
 * Qualifier}/{@link Primary} decorator mechanics this mirrors.
 */
@Service
@Primary
public class TeamScopedInFlightWorkQuery implements GetInFlightWorkQuery {

  private final GetInFlightWorkQuery delegate;

  /**
   * Creates the decorator.
   *
   * @param delegate the real {@code InFlightService} bean, qualified by name
   */
  public TeamScopedInFlightWorkQuery(@Qualifier("inFlightService") GetInFlightWorkQuery delegate) {
    this.delegate = delegate;
  }

  @Override
  public List<TeamInFlightView> inFlight() {
    return TeamScopeFilter.restrictedTo(delegate.inFlight(), TeamInFlightView::teamId);
  }
}
