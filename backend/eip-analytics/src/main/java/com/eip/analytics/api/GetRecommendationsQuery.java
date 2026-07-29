/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.List;

/**
 * Reads the current tenant's rule-based team recommendations ({@code recommendations v0.1}),
 * composed from the computed friction read model and the in-flight work signal. Fails closed when
 * no tenant is bound to the calling thread.
 */
public interface GetRecommendationsQuery {

  /**
   * Builds the recommendations for every team with computed friction, for the current tenant.
   *
   * @return one entry per team, healthy teams included with an empty recommendation list
   */
  List<TeamRecommendationsView> recommendations();
}
