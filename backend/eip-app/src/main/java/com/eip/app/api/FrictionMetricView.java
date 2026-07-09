/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The published definition of a metric (FEAT-031 / FR-056): what it means and how to read it
 * honestly. EIP ships every metric with its purpose, formula, inputs, caveats, and gaming risks —
 * surfacing them is a deliberate trust and anti-gaming feature, not documentation.
 *
 * @param key stable metric key (e.g. {@code engineering_friction})
 * @param name display name
 * @param purpose what question the metric answers
 * @param formula how it is computed (human-readable; mirrors the deterministic implementation)
 * @param inputs the metric's inputs as a JSON object (the raw signals it consumes)
 * @param grain aggregation grain — always {@code team} or coarser for people-adjacent metrics
 *     (never individual; NFR-071)
 * @param caveats known limitations / when to distrust it
 * @param gamingRisks how the metric can be gamed, so it is read with that in mind
 */
public record FrictionMetricView(
    String key,
    String name,
    String purpose,
    String formula,
    JsonNode inputs,
    String grain,
    String caveats,
    String gamingRisks) {}
