/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Analytics persistence adapters (module-internal). {@code JdbcClient} for the read side and {@code
 * JdbcTemplate} batch upserts for projections (BackendPlan §5). Canonical schemas are read with
 * bounded set-based queries only — never per-row loops (no N+1) — and written never (ADR-019).
 * Every statement runs on the tenant-bound transaction opened by the application layer.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.analytics.persistence;

import org.jspecify.annotations.NullMarked;
