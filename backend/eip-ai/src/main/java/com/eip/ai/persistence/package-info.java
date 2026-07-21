/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * AI module persistence adapters (module-internal): {@code JdbcClient} over {@code
 * core.tenant_ai_policy} (V7 migration) and {@code ai.llm_call_audit} (ADR-024). Every statement
 * runs on the tenant-bound transaction opened by the application layer's {@code
 * TenantTransactionRunner} call.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.ai.persistence;

import org.jspecify.annotations.NullMarked;
