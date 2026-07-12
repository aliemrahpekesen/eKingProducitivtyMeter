/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

/**
 * Reads the current tenant's computed Engineering Friction summary (worst-first team breakdown +
 * the metric's own definition) from the RLS-protected read model. Fails closed when no tenant is
 * bound to the calling thread.
 */
public interface GetFrictionSummaryQuery {

  /**
   * Builds the friction summary for the current tenant.
   *
   * @return the summary payload
   */
  FrictionSummaryView summary();
}
