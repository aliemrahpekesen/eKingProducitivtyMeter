/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.List;
import java.util.UUID;

/**
 * One team's rule-based recommendations, alongside the friction score they were derived from. A
 * healthy team (no rule fires) yields an empty {@code recommendations} list — recommendations are
 * never fabricated to fill space. Team-level only (Law 6 / NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param frictionScore the team's current computed friction score (0–100)
 * @param recommendations the fired recommendations, ordered severity {@code CRITICAL} > {@code
 *     WARN} > {@code INFO} then {@code code} ascending
 */
public record TeamRecommendationsView(
    UUID teamId, String teamName, int frictionScore, List<RecommendationView> recommendations) {}
