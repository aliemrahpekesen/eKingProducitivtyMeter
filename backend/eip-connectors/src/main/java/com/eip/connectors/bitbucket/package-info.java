/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Real Bitbucket Cloud connector (M2b): authenticated connectivity probe and paginated full sync of
 * repositories, pull requests, and code reviews into the raw staging path. Emits strict ISO-8601
 * instants and a best-effort Jira issue-key link; carries NO person identifiers — reviews are
 * attributed to the pull request only (NFR-071). Framework-free.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.bitbucket;

import org.jspecify.annotations.NullMarked;
