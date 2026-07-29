/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * AI module application services (module-internal, ADR-024): {@code AiPolicyService} (policy
 * read/update, secret storage via {@code SecretsService}), the pure, unit-testable {@code
 * PromptComposer} (builds the guardrail system prompt + a compact user prompt from the SAME
 * deterministic reads the dashboard/report shows, and self-derives the numeric cross-check
 * whitelist from what it emitted) and {@code NumericCrossChecker} (verifies every number a
 * narrative cites was actually offered to it), and {@code ExplainService} (orchestrates policy
 * load, secret reveal, prompt composition, the provider call — OUTSIDE any transaction — cross-
 * check, and the always-written hash-only audit row). Persistence goes through the module's {@code
 * persistence} repositories.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.ai.application;

import org.jspecify.annotations.NullMarked;
