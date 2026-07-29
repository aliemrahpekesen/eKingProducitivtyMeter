/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Real GitHub connector (M2b Wave 2D): authenticated connectivity probe and bounded, paginated full
 * sync of organization repositories into the raw staging path — pull requests + their first page of
 * reviews, issues (excluding pull requests, which GitHub also lists as issues), and the first page
 * of Actions workflow runs per repository. Emits strict ISO-8601 instants and a best-effort Jira
 * issue-key link; carries NO person identifiers — reviews and issues are attributed to the artifact
 * only (NFR-071).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.github;

import org.jspecify.annotations.NullMarked;
