/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.analytics.api.GetRecommendationsQuery;
import com.eip.analytics.api.TeamRecommendationsView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rule-based recommendations read surface ({@code recommendations v0.1}). A pure DTO adapter
 * (BackendPlan §2.4): delegates to the analytics module's query port; tenancy, transactions, and
 * SQL live behind it. Team-level only (Law 6 / NFR-071).
 */
@RestController
@RequestMapping("/api/v1")
public class InsightsController {

  private final GetRecommendationsQuery recommendations;

  /**
   * Creates the controller.
   *
   * @param recommendations the recommendations query port
   */
  public InsightsController(GetRecommendationsQuery recommendations) {
    this.recommendations = recommendations;
  }

  /**
   * Returns every team's current rule-based recommendations.
   *
   * @return one entry per team with computed friction; healthy teams have an empty recommendation
   *     list
   */
  @GetMapping("/insights/recommendations")
  public List<TeamRecommendationsView> recommendations() {
    return recommendations.recommendations();
  }
}
