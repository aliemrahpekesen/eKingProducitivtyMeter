/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Audit logging service implementations (module-internal), behind {@code
 * com.eip.tenancy.audit.api}: {@link com.eip.tenancy.audit.application.AuditService} (the write
 * path), {@link com.eip.tenancy.audit.application.AuditChainer} (the async hash chainer), {@link
 * com.eip.tenancy.audit.application.AuditChainVerifier} (the chain-integrity verifier), and the
 * shared {@link com.eip.tenancy.audit.application.AuditRecordCanonicalizer} the latter two both use
 * to compute a row's hash.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.audit.application;

import org.jspecify.annotations.NullMarked;
