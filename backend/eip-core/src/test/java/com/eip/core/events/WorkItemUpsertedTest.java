/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkItemUpsertedTest {

  private static final UUID WORK_ITEM_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000b1");

  @Test
  void carriesTheWorkItemIdAndStatus() {
    WorkItemUpserted event = new WorkItemUpserted(WORK_ITEM_ID, "IN_PROGRESS");

    assertThat(event.workItemId()).isEqualTo(WORK_ITEM_ID);
    assertThat(event.status()).isEqualTo("IN_PROGRESS");
    assertThat(event).isInstanceOf(DomainEventPayload.class);
  }

  @Test
  @SuppressWarnings("NullAway") // deliberately passes null to verify the non-null contract
  void rejectsNullFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> new WorkItemUpserted(null, "OPEN"))
        .withMessageContaining("workItemId");
    assertThatNullPointerException()
        .isThrownBy(() -> new WorkItemUpserted(WORK_ITEM_ID, null))
        .withMessageContaining("status");
  }

  @Test
  void honoursValueEquality() {
    WorkItemUpserted a = new WorkItemUpserted(WORK_ITEM_ID, "DONE");
    WorkItemUpserted b = new WorkItemUpserted(WORK_ITEM_ID, "DONE");

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }
}
