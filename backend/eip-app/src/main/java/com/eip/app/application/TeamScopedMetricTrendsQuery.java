/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.analytics.api.GetMetricTrendsQuery;
import com.eip.analytics.api.TeamTrendView;
import com.eip.analytics.api.TrendsView;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Resource-level team-scope enforcement (SecurityModel §4 layer 2, DEBT-012 residual, part 2) over
 * {@link GetMetricTrendsQuery}. See {@link TeamScopedFrictionSummaryQuery} for the {@link
 * Qualifier}/{@link Primary} decorator mechanics this mirrors.
 */
@Service
@Primary
public class TeamScopedMetricTrendsQuery implements GetMetricTrendsQuery {

  private final GetMetricTrendsQuery delegate;

  /**
   * Creates the decorator.
   *
   * @param delegate the real {@code TrendService} bean, qualified by name
   */
  public TeamScopedMetricTrendsQuery(@Qualifier("trendService") GetMetricTrendsQuery delegate) {
    this.delegate = delegate;
  }

  @Override
  public TrendsView trends(int weeks) {
    TrendsView full = delegate.trends(weeks);
    List<TeamTrendView> teams = TeamScopeFilter.restrictedTo(full.teams(), TeamTrendView::teamId);
    return teams == full.teams() ? full : new TrendsView(full.rangeWeeks(), teams);
  }
}
