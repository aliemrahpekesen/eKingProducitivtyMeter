/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Reports application services (module-internal): the deterministic {@code EXEC_SUMMARY} engine
 * (TASK-0022, ADR-023) — composing the analytics module's query ports (never recomputing their
 * metrics), a pure document assembler ({@code ReportComposer}), and a pure, framework-free HTML
 * renderer ({@code ReportHtmlRenderer}). Persistence goes through the module's {@code persistence}
 * repository.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.reports.application;

import org.jspecify.annotations.NullMarked;
