/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The explicit deny-by-default whitelist (SecurityModel §4): marks a {@code /api/v1} handler method
 * that intentionally declares no {@link RequiresPermission} because it authenticates itself by
 * another mechanism entirely — the webhook trigger (its own {@code X-EIP-Webhook-Token}) and the
 * auth-discovery endpoint (pre-login, permitAll by design). Every other unannotated {@code /api/v1}
 * handler is refused by {@link PermissionEnforcementInterceptor}; this annotation is the only
 * sanctioned escape hatch, so its use is always a deliberate, reviewable, single-line decision —
 * never a silent gap.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PermissionExempt {}
