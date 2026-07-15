/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the shared Jira-key extraction: branch-name precedence over title, title fallback, and
 * absence when neither text carries a key.
 */
class JiraKeyExtractorTest {

  @Test
  void branch_name_takes_precedence_over_title() {
    assertThat(JiraKeyExtractor.extract("feature/PLAT-101-checkout", "Unrelated PAY-5 mention"))
        .isEqualTo("PLAT-101");
  }

  @Test
  void falls_back_to_title_when_branch_has_no_key() {
    assertThat(JiraKeyExtractor.extract("chore/cleanup", "Fix PLAT-9 regression"))
        .isEqualTo("PLAT-9");
  }

  @Test
  void returns_null_when_neither_text_has_a_key() {
    assertThat(JiraKeyExtractor.extract("chore/no-ticket", "General cleanup")).isNull();
  }
}
