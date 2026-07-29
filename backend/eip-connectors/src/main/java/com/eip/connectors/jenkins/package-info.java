/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Real Jenkins connector (M2b Wave 2D): authenticated connectivity probe and bounded sync of
 * top-level jobs (folder/multibranch recursion is out of scope for v0.1, documented) into the raw
 * staging path — the most recent builds per job, mapped to canonical {@code build} raw records.
 * Builds with no {@code result} yet (still running) are skipped rather than fabricating a finish
 * instant. Carries NO person identifiers (NFR-071).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.jenkins;

import org.jspecify.annotations.NullMarked;
