/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.ai.api.NarrativeRejectedException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves {@link NumericCrossChecker}'s extraction, normalization, and rejection behavior. */
class NumericCrossCheckerTest {

  @Test
  void accepts_a_narrative_using_only_numbers_from_the_whitelist() {
    Set<String> whitelist = Set.of("85", "12", "3");
    String narrative = "The team's friction score is 85, with 12 items resolved across 3 weeks.";

    Set<String> cited = NumericCrossChecker.verify(narrative, whitelist);

    assertThat(cited).containsExactlyInAnyOrder("85", "12", "3");
  }

  @Test
  void rejects_a_narrative_citing_an_invented_number() {
    Set<String> whitelist = Set.of("85", "12");
    String narrative = "The friction score is 85, but next quarter it could reach 99.";

    assertThatThrownBy(() -> NumericCrossChecker.verify(narrative, whitelist))
        .isInstanceOf(NarrativeRejectedException.class);
  }

  @Test
  void rejection_message_never_echoes_the_invented_number_itself() {
    Set<String> whitelist = Set.of();
    String narrative = "A completely fabricated 4242 appears here.";

    assertThatThrownBy(() -> NumericCrossChecker.verify(narrative, whitelist))
        .isInstanceOf(NarrativeRejectedException.class)
        .hasMessageNotContaining("4242");
  }

  @Test
  void normalizes_a_percent_suffix_to_match_the_bare_number() {
    assertThat(NumericCrossChecker.extractNormalized("blocked=85%")).containsExactly("85");
    assertThat(NumericCrossChecker.verify("blocked is 85%", Set.of("85"))).containsExactly("85");
  }

  @Test
  void normalizes_a_trailing_decimal_zero_to_match_the_integer() {
    assertThat(NumericCrossChecker.extractNormalized("avgCycle=17.0h")).containsExactly("17");
    assertThat(NumericCrossChecker.verify("average cycle time is 17.0 hours", Set.of("17")))
        .containsExactly("17");
  }

  @Test
  void strips_thousands_separators_before_comparing() {
    assertThat(NumericCrossChecker.extractNormalized("itemsResolved=5,000"))
        .containsExactly("5000");
  }

  @Test
  void a_narrative_with_no_numbers_is_always_accepted() {
    assertThat(NumericCrossChecker.verify("The team is trending well.", Set.of())).isEmpty();
  }
}
