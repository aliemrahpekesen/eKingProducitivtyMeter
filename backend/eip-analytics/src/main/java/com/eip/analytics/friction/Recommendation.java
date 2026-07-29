/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

import java.util.List;

/**
 * One rule-based finding produced by {@link RecommendationEngine}. Pure value type — the {@code
 * com.eip.analytics.api} layer maps it 1:1 to {@code RecommendationView}.
 *
 * @param code the stable rule code (e.g. {@code R-REVIEW-WAIT})
 * @param severity {@code INFO} | {@code WARN} | {@code CRITICAL}
 * @param title a short human-readable summary of the finding
 * @param rationale the finding explained with the team's actual numbers
 * @param actions suggested next steps, most impactful first
 * @param metricRefs the input metrics that drove the rule, as {@code "name=value"} strings
 */
public record Recommendation(
    String code,
    String severity,
    String title,
    String rationale,
    List<String> actions,
    List<String> metricRefs) {}
