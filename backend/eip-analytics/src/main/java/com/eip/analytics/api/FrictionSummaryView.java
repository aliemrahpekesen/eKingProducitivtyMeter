/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Engineering Friction summary — the first dashboard-card payload. Carries the metric's own
 * definition (so the card can show what it means and how it can mislead), its version and
 * computation timestamp, and a per-team breakdown sorted worst-first, so {@code teams.get(0)} is
 * the top bottleneck. A composed summary resource, not a queryable collection — {@code teams} is a
 * bounded embedded array (one row per team), so the cursor-pagination envelope (APIDesign §1.4)
 * does not apply.
 *
 * @param metric the friction metric definition, or {@code null} if not yet registered for the
 *     tenant
 * @param metricVersion the computed metric version (e.g. {@code engineering_friction_v0.1}), or
 *     {@code null} if nothing has been computed yet
 * @param computedAt when the friction was last computed (ISO-8601), or {@code null} if never
 * @param simulation whether the underlying data is simulated (always true in v0.1)
 * @param teams per-team friction, sorted by {@code frictionScore} desc then name then id
 * @param teamsReporting number of teams with computed friction
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrictionSummaryView(
    @Nullable FrictionMetricView metric,
    @Nullable String metricVersion,
    @Nullable String computedAt,
    boolean simulation,
    List<TeamFrictionView> teams,
    int teamsReporting) {}
