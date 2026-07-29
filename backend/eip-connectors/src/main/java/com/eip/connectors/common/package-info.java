/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Shared, framework-free helpers reused by real connector implementations: Jira-issue-key
 * extraction from free text (branch names, titles) and per-source timestamp normalization to strict
 * ISO-8601 instants.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.common;

import org.jspecify.annotations.NullMarked;
