/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import org.jspecify.annotations.Nullable;

/**
 * Pre-login auth discovery payload (SecurityModel §3): tells the frontend which auth mode is active
 * and, in {@code OIDC} mode, the issuer + client id it needs to start the Authorization Code + PKCE
 * flow. Served by {@code GET /api/v1/session/auth}, permitAll — a client cannot know which mode to
 * speak until it has asked.
 *
 * @param mode {@code "HEADER"} or {@code "OIDC"}
 * @param issuer the OIDC issuer, or {@code null} in {@code HEADER} mode
 * @param clientId the fixed public client id ({@code "eip-frontend"}), or {@code null} in {@code
 *     HEADER} mode
 */
public record AuthConfigView(String mode, @Nullable String issuer, @Nullable String clientId) {}
