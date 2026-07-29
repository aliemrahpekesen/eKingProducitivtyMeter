/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the shared timestamp conversion for both Bitbucket's colon-delimited ISO offset and
 * SonarQube's compact offset (with and without milliseconds), plus the strict-ISO-8601 fallback.
 */
class SourceTimestampsTest {

  @Test
  void converts_bitbucket_style_iso_offset_with_microseconds() {
    assertThat(SourceTimestamps.fromIsoOffset("2026-01-05T09:00:00.000000+00:00"))
        .isEqualTo("2026-01-05T09:00:00Z");
  }

  @Test
  void converts_sonarqube_style_compact_offset() {
    assertThat(SourceTimestamps.fromCompactOffset("2026-01-05T09:00:00+0000"))
        .isEqualTo("2026-01-05T09:00:00Z");
  }

  @Test
  void converts_compact_offset_with_milliseconds() {
    assertThat(SourceTimestamps.fromCompactOffset("2026-01-05T09:00:00.500+0000"))
        .isEqualTo("2026-01-05T09:00:00.500Z");
  }

  @Test
  void compact_offset_falls_back_to_strict_iso_8601() {
    assertThat(SourceTimestamps.fromCompactOffset("2026-01-05T09:00:00Z"))
        .isEqualTo("2026-01-05T09:00:00Z");
  }
}
