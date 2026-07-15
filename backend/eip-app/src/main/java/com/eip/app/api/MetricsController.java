/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.analytics.api.GetInFlightWorkQuery;
import com.eip.analytics.api.GetMetricTrendsQuery;
import com.eip.analytics.api.TeamInFlightView;
import com.eip.analytics.api.TrendsView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deterministic metric-trends and in-flight-work read surface. A pure DTO adapter (BackendPlan
 * §2.4): both endpoints delegate to the analytics module's query ports; tenancy, transactions, and
 * SQL live behind them. Team-level only (Law 6 / NFR-071).
 */
@RestController
@RequestMapping("/api/v1")
public class MetricsController {

  private final GetMetricTrendsQuery trends;
  private final GetInFlightWorkQuery inFlight;

  /**
   * Creates the controller.
   *
   * @param trends the metric-trends query port
   * @param inFlight the in-flight-work query port
   */
  public MetricsController(GetMetricTrendsQuery trends, GetInFlightWorkQuery inFlight) {
    this.trends = trends;
    this.inFlight = inFlight;
  }

  /**
   * Returns the current tenant's weekly team metric trends.
   *
   * @param weeks the requested window width, in weeks (clamped 4..52 server-side)
   * @return the trends payload
   */
  @GetMapping("/metrics/trends")
  public TrendsView trends(@RequestParam(defaultValue = "12") int weeks) {
    return trends.trends(weeks);
  }

  /**
   * Returns the current tenant's in-flight (unresolved) work, grouped by team.
   *
   * @return one entry per team with at least one unresolved work item
   */
  @GetMapping("/metrics/in-flight")
  public List<TeamInFlightView> inFlight() {
    return inFlight.inFlight();
  }
}
