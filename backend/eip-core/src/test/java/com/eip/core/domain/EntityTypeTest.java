/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EntityTypeTest {

  @Test
  void includesTheSharedKernelAndWorkManagementEntities() {
    assertThat(EntityType.values())
        .contains(
            EntityType.WORK_ITEM,
            EntityType.MEMBER,
            EntityType.PULL_REQUEST,
            EntityType.RELEASE,
            EntityType.DEPLOYMENT,
            EntityType.INCIDENT,
            EntityType.ENTITY_LINK);
  }

  @Test
  void valueOfRoundTripsEachName() {
    for (EntityType type : EntityType.values()) {
      assertThat(EntityType.valueOf(type.name())).isSameAs(type);
    }
  }
}
