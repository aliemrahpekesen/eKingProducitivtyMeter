/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The real Jira connector (M2): authenticated connectivity probe and paginated full sync of work
 * items + workflow transitions (changelog) into the raw staging path. Emits canonical workflow
 * states and strict ISO-8601 instants; carries NO person identifiers — issues and transitions are
 * attributed to projects/teams only (NFR-071).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.jira;

import org.jspecify.annotations.NullMarked;
