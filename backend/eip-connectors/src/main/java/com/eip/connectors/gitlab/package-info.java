/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Real GitLab connector (M2b Wave 2D): authenticated connectivity probe and bounded, paginated full
 * sync of group projects into the raw staging path — merge requests and the first page of pipelines
 * per project. Emits strict ISO-8601 instants and a best-effort Jira issue-key link; never
 * fabricates a code-review timestamp (GitLab's approval state carries no per-approval instant, so
 * v0.1 emits no {@code code_review} records for this source, documented). Carries NO person
 * identifiers (NFR-071).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.gitlab;

import org.jspecify.annotations.NullMarked;
