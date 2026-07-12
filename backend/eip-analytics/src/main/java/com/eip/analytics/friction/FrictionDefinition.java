/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

/**
 * The versioned Engineering Friction v0.1 metric definition (FR-062: every metric ships purpose,
 * formula, inputs, grain, caveats, and gaming risks). The compute service upserts these into {@code
 * analytics.metric_definition} so the API and UI can surface them verbatim. {@code METRIC_KEY} is
 * the stable catalog key; {@code VERSION} tags the read model and {@code metric_fact} rows.
 */
public final class FrictionDefinition {

  /** Stable catalog key (unchanged across versions so the API query is stable). */
  public static final String METRIC_KEY = "engineering_friction";

  /** Version tag for the read model + facts. */
  public static final String VERSION = "engineering_friction_v0.1";

  /** Definition version integer stored on facts / active_version. */
  public static final int DEFINITION_VERSION = 1;

  /** Human name. */
  public static final String NAME = "Engineering Friction (v0.1, experimental)";

  /** Purpose statement. */
  public static final String PURPOSE =
      "Where a team's delivery time is lost: the share of work-item cycle time spent waiting"
          + " (blocked or in review) rather than actively progressing, plus a rework penalty."
          + " Team-level only; never ranks or scores individuals.";

  /** Human-readable formula. */
  public static final String FORMULA =
      "waitingRatio = (blocked + reviewWait) / totalCycle;"
          + " reworkPerItem = reworkCount / workItems;"
          + " friction = round(min(100, 100*waitingRatio + 30*reworkPerItem)). EXPERIMENTAL v0.1.";

  /** Inputs, as a JSON object literal for the {@code inputs jsonb} column. */
  public static final String INPUTS_JSON =
      "{\"signals\":[\"work_item_transitions\",\"blocked_time\",\"review_wait_time\","
          + "\"rework_count\",\"cycle_time\"],\"grain\":\"team\",\"version\":\"v0.1\","
          + "\"deterministic\":true,\"experimental\":true,\"source\":\"simulation\"}";

  /** Grain. */
  public static final String GRAIN = "team";

  /** Caveats. */
  public static final String CAVEATS =
      "EXPERIMENTAL v0.1 computed from simulation data. A relative team signal, not an SLA or"
          + " a cross-team ranking; short-cycle teams can show a high waiting share from small"
          + " absolute waits. Team-level only (NFR-071).";

  /** Gaming risks. */
  public static final String GAMING_RISKS =
      "Skipping or rubber-stamping reviews, splitting items to shrink per-item cycle time, or"
          + " avoiding the BLOCKED state understates friction without improving real flow.";

  private FrictionDefinition() {}
}
