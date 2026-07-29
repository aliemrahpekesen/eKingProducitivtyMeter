/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SchemaVersionTest {

  @Test
  void buildsFromComponentsAndRendersDotted() {
    SchemaVersion v = SchemaVersion.of(2, 3);

    assertThat(v.major()).isEqualTo(2);
    assertThat(v.minor()).isEqualTo(3);
    assertThat(v).hasToString("2.3");
    assertThat(v).isEqualTo(new SchemaVersion(2, 3)).hasSameHashCodeAs(new SchemaVersion(2, 3));
  }

  @Test
  void parsesDottedText() {
    assertThat(SchemaVersion.parse("1.0")).isEqualTo(SchemaVersion.of(1, 0));
    assertThat(SchemaVersion.parse("10.25")).isEqualTo(SchemaVersion.of(10, 25));
  }

  @Test
  @SuppressWarnings("NullAway") // deliberately passes null to verify the non-null contract
  void rejectsMalformedText() {
    assertThatNullPointerException().isThrownBy(() -> SchemaVersion.parse(null));
    assertThatThrownBy(() -> SchemaVersion.parse("1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MAJOR.MINOR");
    assertThatThrownBy(() -> SchemaVersion.parse("1.2.3"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaVersion.parse("x.y"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-numeric");
  }

  @Test
  void rejectsOutOfRangeComponents() {
    assertThatThrownBy(() -> SchemaVersion.of(0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("major");
    assertThatThrownBy(() -> SchemaVersion.of(1, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minor");
  }
}
