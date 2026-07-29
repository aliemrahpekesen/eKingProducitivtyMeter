/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Real SonarQube connector (M2b): authenticated connectivity probe and paginated full sync of
 * project + pull-request quality gates into the raw staging path. Never fabricates an evaluation
 * timestamp — gates with no analysis history yet are skipped. Framework-free.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.sonarqube;

import org.jspecify.annotations.NullMarked;
