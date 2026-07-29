/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

/**
 * Reads the current tenant's weekly team metric trends, anchored to the tenant's own data rather
 * than wall-clock time. Fails closed when no tenant is bound to the calling thread.
 */
public interface GetMetricTrendsQuery {

  /**
   * Builds the metric trends for the current tenant.
   *
   * @param weeks the requested window width, in weeks (the implementation clamps to 4..52)
   * @return the trends payload
   */
  TrendsView trends(int weeks);
}
