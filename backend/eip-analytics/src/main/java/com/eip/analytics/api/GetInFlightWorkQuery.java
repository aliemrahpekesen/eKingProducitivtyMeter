/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.List;

/**
 * Reads the current tenant's in-flight (unresolved) work, grouped by team. Fails closed when no
 * tenant is bound to the calling thread.
 */
public interface GetInFlightWorkQuery {

  /**
   * Builds the in-flight work view for the current tenant.
   *
   * @return one entry per team with at least one unresolved work item
   */
  List<TeamInFlightView> inFlight();
}
