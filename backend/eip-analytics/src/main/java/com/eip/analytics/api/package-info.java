/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The analytics module's public API (BackendPlan §3 named interface): friction computation and
 * query ports plus their response DTOs. The pure metric engine ({@code friction}) and the
 * application/persistence layers are module-private — consumers see only these contracts.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
@org.springframework.modulith.NamedInterface("api")
package com.eip.analytics.api;

import org.jspecify.annotations.NullMarked;
