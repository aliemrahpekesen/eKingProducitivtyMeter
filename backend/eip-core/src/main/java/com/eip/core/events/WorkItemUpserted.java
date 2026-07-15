/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.events;

import java.util.Objects;
import java.util.UUID;

/**
 * The canonical body of an {@code EntityType.WORK_ITEM} {@code "upserted"} event (BackendPlan §6):
 * emitted once per canonical work item whose row actually changed during normalization. Minimal by
 * design (v0.1) — enough for a downstream consumer (e.g. friction recomputation) to know which work
 * item changed without re-fetching the canonical row; richer payloads arrive with later phases.
 *
 * @param workItemId the canonical work item id ({@code work.work_item.id})
 * @param status the work item's status at emission time
 */
public record WorkItemUpserted(UUID workItemId, String status) implements DomainEventPayload {

  public WorkItemUpserted {
    Objects.requireNonNull(workItemId, "workItemId");
    Objects.requireNonNull(status, "status");
  }
}
