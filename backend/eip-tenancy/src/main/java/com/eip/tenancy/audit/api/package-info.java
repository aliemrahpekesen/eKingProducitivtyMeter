/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Audit logging ports (DEBT-024 Wave 3A, SecurityModel §11): {@link
 * com.eip.tenancy.audit.api.AuditEvent} is the DTO every future call site (Wave 3B) constructs and
 * passes to {@link com.eip.tenancy.audit.api.RecordAuditEventUseCase}; {@link
 * com.eip.tenancy.audit.api.AuditChainerUseCase} and {@link
 * com.eip.tenancy.audit.api.AuditChainVerifierUseCase} are the ports {@code eip-app}'s scheduled
 * beans invoke (BackendPlan §2.4 layering: eip-app depends on api ports only, never on {@code
 * .application}/{@code .persistence} internals).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.audit.api;

import org.jspecify.annotations.NullMarked;
