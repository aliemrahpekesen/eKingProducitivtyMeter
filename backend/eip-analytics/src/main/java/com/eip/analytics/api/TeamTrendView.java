/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.List;
import java.util.UUID;

/**
 * One team's weekly metric trend: its display name and the weeks with at least one resolved work
 * item, ascending by {@code weekStart}. Weeks with no resolved items are omitted — never
 * zero-filled (so a gap in the series means no data, not a computed zero). Team-level only (Law 6 /
 * NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param points the team's weekly points, ascending by {@code weekStart}
 */
public record TeamTrendView(UUID teamId, String teamName, List<TrendPointView> points) {}
