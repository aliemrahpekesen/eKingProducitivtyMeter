/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.analytics.api.GetRecommendationsQuery;
import com.eip.analytics.api.TeamRecommendationsView;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Resource-level team-scope enforcement (SecurityModel §4 layer 2, DEBT-012 residual, part 2) over
 * {@link GetRecommendationsQuery}. See {@link TeamScopedFrictionSummaryQuery} for the {@link
 * Qualifier}/{@link Primary} decorator mechanics this mirrors.
 */
@Service
@Primary
public class TeamScopedRecommendationsQuery implements GetRecommendationsQuery {

  private final GetRecommendationsQuery delegate;

  /**
   * Creates the decorator.
   *
   * @param delegate the real {@code RecommendationService} bean, qualified by name
   */
  public TeamScopedRecommendationsQuery(
      @Qualifier("recommendationService") GetRecommendationsQuery delegate) {
    this.delegate = delegate;
  }

  @Override
  public List<TeamRecommendationsView> recommendations() {
    return TeamScopeFilter.restrictedTo(
        delegate.recommendations(), TeamRecommendationsView::teamId);
  }
}
