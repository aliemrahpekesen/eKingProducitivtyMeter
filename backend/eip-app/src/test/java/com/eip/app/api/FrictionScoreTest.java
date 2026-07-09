/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FrictionScoreTest {

  private static final long DAY = 86_400L;

  @Test
  void weightsTheSignalsPerTheDocumentedFormula() {
    // 10*3 + 6*4 + 4*5 = 30 + 24 + 20 = 74
    assertThat(FrictionScore.of(3, 5 * DAY, 4)).isEqualTo(74);
  }

  @Test
  void isZeroWhenThereIsNoFriction() {
    assertThat(FrictionScore.of(0, 0, 0)).isZero();
  }

  @Test
  void capsAtMaxScore() {
    assertThat(FrictionScore.of(100, 100 * DAY, 100)).isEqualTo(FrictionScore.MAX_SCORE);
  }

  @Test
  void clampsNegativeSignalsToZero() {
    assertThat(FrictionScore.of(-5, -DAY, -3)).isZero();
  }

  @Test
  void isDeterministicForTheSameInputs() {
    assertThat(FrictionScore.of(1, 2 * DAY, 2)).isEqualTo(FrictionScore.of(1, 2 * DAY, 2));
  }
}
