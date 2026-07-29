/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.List;

/**
 * The metric-trends payload for {@code GET /api/v1/metrics/trends}: a fixed-width window of weekly
 * team metrics anchored to the tenant's own data (the latest resolved work item), not wall-clock
 * time, so the same underlying data always yields a byte-identical response. Teams with no resolved
 * items anywhere in the window are absent from {@code teams}.
 *
 * @param rangeWeeks the requested window width, in weeks (clamped 4..52)
 * @param teams per-team weekly trends
 */
public record TrendsView(int rangeWeeks, List<TeamTrendView> teams) {}
