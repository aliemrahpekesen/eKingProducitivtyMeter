/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.core.error.ValidationException;
import org.junit.jupiter.api.Test;

/** Proves the {@code sort} query-param whitelist (APIDesign §1.4; DEBT-010). */
class ConnectorSortTest {

  @Test
  void null_or_blank_defaults_to_ascending_name() {
    assertThat(ConnectorSort.fromParam(null)).isEqualTo(ConnectorSort.NAME_ASC);
    assertThat(ConnectorSort.fromParam("")).isEqualTo(ConnectorSort.NAME_ASC);
    assertThat(ConnectorSort.fromParam("  ")).isEqualTo(ConnectorSort.NAME_ASC);
  }

  @Test
  void every_whitelisted_value_resolves_to_its_sort() {
    assertThat(ConnectorSort.fromParam("name")).isEqualTo(ConnectorSort.NAME_ASC);
    assertThat(ConnectorSort.fromParam("-name")).isEqualTo(ConnectorSort.NAME_DESC);
    assertThat(ConnectorSort.fromParam("createdAt")).isEqualTo(ConnectorSort.CREATED_AT_ASC);
    assertThat(ConnectorSort.fromParam("-createdAt")).isEqualTo(ConnectorSort.CREATED_AT_DESC);
  }

  @Test
  void an_unknown_value_is_rejected_naming_every_allowed_value() {
    assertThatThrownBy(() -> ConnectorSort.fromParam("bogus"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bogus")
        .hasMessageContaining("name")
        .hasMessageContaining("-name")
        .hasMessageContaining("createdAt")
        .hasMessageContaining("-createdAt");
  }
}
