/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * {@code JdbcClient} adapter over {@code audit.audit_event} (module-internal). Append-only in
 * spirit: the only UPDATE any method here issues sets {@code prev_hash}/{@code hash} on a row whose
 * {@code hash IS NULL} (the chainer's job) — see this module's root package-info for why per-table
 * DB grants are not (yet) the enforcement mechanism.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.audit.persistence;

import org.jspecify.annotations.NullMarked;
