/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The ingestion module's public API (BackendPlan §3 named interface): the use-case ports other
 * modules may call — deterministic simulation ingestion into raw staging and normalization of
 * staged records into the canonical model — plus their result/exception types. Application and
 * persistence internals are module-private.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
@org.springframework.modulith.NamedInterface("api")
package com.eip.ingestion.api;

import org.jspecify.annotations.NullMarked;
