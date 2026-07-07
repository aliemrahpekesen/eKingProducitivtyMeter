/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkItemTypeTest {

  @Test
  void coversExactlyTheCanonicalWorkItemTypes() {
    // given / when
    WorkItemType[] values = WorkItemType.values();

    // then — the closed set from DomainModel §5, no more, no less
    assertThat(values)
        .containsExactly(
            WorkItemType.EPIC,
            WorkItemType.FEATURE,
            WorkItemType.STORY,
            WorkItemType.TASK,
            WorkItemType.BUG,
            WorkItemType.INCIDENT_TICKET);
  }

  @Test
  void valueOfRoundTripsEachName() {
    for (WorkItemType type : WorkItemType.values()) {
      assertThat(WorkItemType.valueOf(type.name())).isSameAs(type);
    }
  }
}
