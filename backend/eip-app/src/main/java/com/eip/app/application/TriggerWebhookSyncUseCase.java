/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Handles an inbound webhook trigger for one connector: the webhook is a TRIGGER only, never a data
 * path (SecurityModel — a source system's webhook payload is never trusted or parsed; on acceptance
 * this dispatches the connector's own authenticated incremental fetch, single-sourced and
 * idempotent).
 */
public interface TriggerWebhookSyncUseCase {

  /**
   * Validates the webhook token for one connector and, if accepted, dispatches an asynchronous
   * incremental sync for it.
   *
   * @param tenantId the tenant the webhook path names (external callers cannot set {@code
   *     X-EIP-Tenant})
   * @param connectorId the target connector
   * @param suppliedToken the {@code X-EIP-Webhook-Token} header value, or {@code null} if absent
   * @return the outcome
   */
  WebhookOutcome trigger(UUID tenantId, UUID connectorId, @Nullable String suppliedToken);

  /** The result of one webhook trigger attempt. */
  enum WebhookOutcome {
    /** Token valid, not debounced: an incremental sync was dispatched. */
    ACCEPTED,
    /** Token valid, but a trigger for this connector was already accepted within the window. */
    DEBOUNCED,
    /** The connector's configured token is missing, or the supplied token does not match it. */
    UNAUTHORIZED,
    /** No connector with this id is visible under the path's tenant. */
    NOT_FOUND
  }
}
