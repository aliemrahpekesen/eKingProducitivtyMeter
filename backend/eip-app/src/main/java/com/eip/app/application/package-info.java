/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * eip-app application ports and composition-level use cases (BackendPlan §2.4). Controllers call
 * these ports only; the friction pipeline orchestrator composes the ingestion and analytics module
 * use cases without owning any normalization, correlation, metric, or persistence logic itself.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.application;

import org.jspecify.annotations.NullMarked;
