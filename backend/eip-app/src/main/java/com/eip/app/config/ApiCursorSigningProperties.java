/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Pagination-cursor HMAC signing key (APIDesign §1.4, DEBT-010): {@link
 * com.eip.app.persistence.ConnectorCursor} and {@code com.eip.reports.persistence.ReportCursor}
 * (the deliberately-duplicated {@code eip-reports} cursor, TASK-0022) both sign and expire their
 * opaque cursors with this key. {@code cursorSigningKey} is the default is a well-known DEV-ONLY
 * key so local environments work out of the box — production deployments MUST override it via
 * {@code EIP_API_CURSOR_SIGNING_KEY} (the prod/preprod env files ship {@code CHANGE_ME} per
 * DEBT-005, and {@code ProductionSecretsGuard} refuses prod/preprod boot while this or the other
 * guarded secrets remain blank/CHANGE_ME/a known dev fixture).
 *
 * <p>Deliberately a separate key from {@link EipSecretsProperties#masterKey}: a cursor signature is
 * a transient, non-reversible integrity check with its own rotation lifecycle (rotating it merely
 * invalidates in-flight pagination, forcing clients to restart from page 1 — rotating the envelope-
 * encryption master key is a very different, much heavier operation), so the two must never share
 * one secret.
 *
 * @param cursorSigningKey base64 of exactly 32 key bytes
 */
@ConfigurationProperties(prefix = "eip.api")
@Validated
public record ApiCursorSigningProperties(
    @NotBlank @DefaultValue(DEV_ONLY_CURSOR_SIGNING_KEY) String cursorSigningKey) {

  /** Base64 of the 32-byte DEV-ONLY signing key ("EIP-DEV-ONLY-CURSOR-SIGN-KEY-32!"). */
  public static final String DEV_ONLY_CURSOR_SIGNING_KEY =
      "RUlQLURFVi1PTkxZLUNVUlNPUi1TSUdOLUtFWS0zMiE=";
}
