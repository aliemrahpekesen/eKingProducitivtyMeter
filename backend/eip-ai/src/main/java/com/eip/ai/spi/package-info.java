/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The LLM provider SPI (module-internal to {@code eip-ai}; not a Modulith named interface —
 * consumers outside this module go through {@code com.eip.ai.api} instead). Pure Java, framework-
 * free by design so a provider implementation never needs Spring/JDBC to satisfy the contract
 * (M6-A, ADR-024).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.ai.spi;

import org.jspecify.annotations.NullMarked;
