/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The reports module's public API (BackendPlan §3 named interface): the deterministic
 * report-generation/read ports and their response DTOs (TASK-0022, ADR-023). The composition
 * (application) and storage (persistence) layers are module-private — consumers see only these
 * contracts.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
@org.springframework.modulith.NamedInterface("api")
package com.eip.reports.api;

import org.jspecify.annotations.NullMarked;
