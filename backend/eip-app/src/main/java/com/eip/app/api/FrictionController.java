/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Engineering Friction read surface — the first differentiating metric ("where is engineering
 * time lost"). Read tenant-scoped under RLS; team-level only (Law 6 / NFR-071). Deterministic: the
 * response is a pure function of the seeded flow read model, with no AI and no clock.
 */
@RestController
@RequestMapping("/api/v1")
public class FrictionController {

  private final FrictionSummaryService friction;

  public FrictionController(FrictionSummaryService friction) {
    this.friction = friction;
  }

  /**
   * Returns the current tenant's Engineering Friction summary.
   *
   * @return the metric definition + worst-first per-team friction breakdown
   */
  @GetMapping("/friction/summary")
  public FrictionSummaryView summary() {
    return friction.summary();
  }
}
