/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The AI module's public API (BackendPlan §3 named interface, M6-A/ADR-024): the per-tenant AI
 * policy management ports, the opt-in explanation/narrative ports, their response DTOs, and the
 * unchecked failure modes a caller must handle. The composition (application) and storage
 * (persistence) layers are module-private — consumers see only these contracts.
 *
 * <p>Every port here fails closed: AI is OFF by default per tenant ({@link
 * com.eip.ai.api.ManageAiPolicyUseCase}), never computes a metric itself, never sees
 * individual-level data (none exists in the canonical model — NFR-071), and every generated
 * narrative is numerically cross-checked against the deterministic data it was given before it is
 * returned ({@link com.eip.ai.api.NarrativeRejectedException}).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
@org.springframework.modulith.NamedInterface("api")
package com.eip.ai.api;

import org.jspecify.annotations.NullMarked;
