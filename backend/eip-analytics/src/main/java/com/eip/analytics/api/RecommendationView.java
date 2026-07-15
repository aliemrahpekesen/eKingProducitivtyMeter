/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.List;

/**
 * One rule-based recommendation fired for a team by the Engineering Friction v0.1 recommendation
 * engine ({@code recommendations v0.1}, {@link com.eip.analytics.friction.RecommendationEngine}).
 * Evidence-linked: {@code rationale} embeds the actual numbers that triggered the rule and {@code
 * metricRefs} names the inputs, so the recommendation is never an unexplained assertion. Team-level
 * only (Law 6 / NFR-071).
 *
 * @param code the stable rule code (e.g. {@code R-REVIEW-WAIT})
 * @param severity {@code INFO} | {@code WARN} | {@code CRITICAL}
 * @param title a short human-readable summary of the finding
 * @param rationale the finding explained with the team's actual numbers
 * @param actions suggested next steps, most impactful first
 * @param metricRefs the input metrics that drove the rule, as {@code "name=value"} strings (e.g.
 *     {@code "reviewWaitPct=57"})
 */
public record RecommendationView(
    String code,
    String severity,
    String title,
    String rationale,
    List<String> actions,
    List<String> metricRefs) {}
