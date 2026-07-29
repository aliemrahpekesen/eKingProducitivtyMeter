/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultUuidV7GeneratorTest {

  private static final Instant FIXED = Instant.parse("2026-07-07T10:15:30.500Z");

  private static int compareUnsigned(UUID a, UUID b) {
    int cmp = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
    return cmp != 0
        ? cmp
        : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
  }

  @Test
  void producesVersion7IetfVariantUuidsCarryingTheClockTimestamp() {
    // given
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    UuidV7Generator generator = new DefaultUuidV7Generator(clock, new Random(42));

    // when
    UUID id = generator.generate();

    // then
    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    long timestampMillis = id.getMostSignificantBits() >>> 16;
    assertThat(timestampMillis).isEqualTo(FIXED.toEpochMilli());
  }

  @Test
  void isStrictlyMonotonicWithinAndAcrossMillisecondOverflow() {
    // given — a fixed clock forces the intra-millisecond counter, including its 4096-slot overflow
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    UuidV7Generator generator = new DefaultUuidV7Generator(clock, new Random(1));
    Set<UUID> seen = new HashSet<>();

    // when
    UUID previous = generator.generate();
    seen.add(previous);
    for (int i = 0; i < 5_000; i++) {
      UUID next = generator.generate();
      // then — each value is unique and strictly greater than the last
      assertThat(compareUnsigned(next, previous)).isPositive();
      assertThat(seen.add(next)).isTrue();
      assertThat(next.version()).isEqualTo(7);
      previous = next;
    }
  }

  @Test
  void resetsCounterAndAdvancesTimestampOnNewMillisecond() {
    // given
    MutableClock clock = new MutableClock(FIXED);
    UuidV7Generator generator = new DefaultUuidV7Generator(clock, new Random(7));

    // when
    UUID first = generator.generate();
    clock.setMillis(FIXED.toEpochMilli() + 1);
    UUID second = generator.generate();

    // then
    assertThat(first.getMostSignificantBits() >>> 16).isEqualTo(FIXED.toEpochMilli());
    assertThat(second.getMostSignificantBits() >>> 16).isEqualTo(FIXED.toEpochMilli() + 1);
    assertThat(compareUnsigned(second, first)).isPositive();
  }

  @Test
  void neverEmitsSmallerValueWhenClockRunsBackwards() {
    // given
    MutableClock clock = new MutableClock(FIXED);
    UuidV7Generator generator = new DefaultUuidV7Generator(clock, new Random(9));

    // when — clock regresses 10ms
    UUID first = generator.generate();
    clock.setMillis(FIXED.toEpochMilli() - 10);
    UUID second = generator.generate();

    // then — time is held, monotonicity preserved
    assertThat(compareUnsigned(second, first)).isPositive();
    assertThat(second.getMostSignificantBits() >>> 16).isEqualTo(FIXED.toEpochMilli());
  }

  @Test
  void isDeterministicForAFixedClockAndSeededRandom() {
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    UuidV7Generator a = new DefaultUuidV7Generator(clock, new Random(123));
    UuidV7Generator b = new DefaultUuidV7Generator(clock, new Random(123));

    assertThat(a.generate()).isEqualTo(b.generate());
  }

  @Test
  void secureRandomConstructorProducesValidVersion7() {
    UuidV7Generator generator = new DefaultUuidV7Generator(Clock.systemUTC());

    assertThat(generator.generate().version()).isEqualTo(7);
  }

  @Test
  @SuppressWarnings("NullAway") // deliberately passes null to verify the non-null contract
  void rejectsNullDependencies() {
    Clock clock = Clock.systemUTC();
    assertThatNullPointerException().isThrownBy(() -> new DefaultUuidV7Generator(null));
    assertThatNullPointerException().isThrownBy(() -> new DefaultUuidV7Generator(clock, null));
  }

  /** Minimal adjustable UTC clock so counter-reset and backwards-clock branches are testable. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant start) {
      this.instant = start;
    }

    private void setMillis(long epochMilli) {
      this.instant = Instant.ofEpochMilli(epochMilli);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
