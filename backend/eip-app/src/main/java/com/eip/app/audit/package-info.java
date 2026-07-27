/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Scheduled triggers for the audit hash-chain subsystem (DEBT-024 Wave 3A, SecurityModel §11): the
 * {@code @Scheduled} chainer sweep and chain-integrity verifier sweep. Both beans here only invoke
 * {@code com.eip.tenancy.audit.api} ports — the chaining/verification logic itself lives in {@code
 * eip-tenancy} (ADR-019: eip-app wires and invokes use cases, never owns pipeline business logic).
 * Gated by {@code eip.audit.enabled} (default {@code true}).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.audit;

import org.jspecify.annotations.NullMarked;
