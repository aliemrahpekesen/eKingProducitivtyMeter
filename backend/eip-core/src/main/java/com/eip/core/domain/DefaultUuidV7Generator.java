/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * Default {@link UuidV7Generator} producing RFC 9562 version-7 UUIDs from an injected {@link Clock}
 * (BackendPlan §2.5 — {@code Clock} is injected everywhere for testability).
 *
 * <p>Layout: 48-bit big-endian Unix-epoch milliseconds, 4-bit version, a 12-bit intra-millisecond
 * monotonic counter (the {@code rand_a} field, RFC 9562 §6.2 method 1), the 2-bit IETF variant, and
 * 62 bits of randomness. The counter guarantees strictly increasing values within a single
 * millisecond; on the (astronomically unlikely) 4096-per-ms overflow the timestamp is nudged
 * forward to preserve monotonicity. A {@link ReentrantLock} guards the counter — never {@code
 * synchronized}, which would pin virtual threads (CodingStandards §2.1).
 */
public final class DefaultUuidV7Generator implements UuidV7Generator {

  private static final long MILLIS_MASK = 0xFFFF_FFFF_FFFFL;
  private static final int COUNTER_MASK = 0xFFF;
  private static final long VERSION_7 = 0x7L << 12;
  private static final long VARIANT_IETF = 1L << 63;
  private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

  private final Clock clock;
  private final RandomGenerator random;
  private final ReentrantLock lock = new ReentrantLock();

  private long lastMillis = -1L;
  private int counter;

  /**
   * Creates a generator over the given clock with a {@link SecureRandom} entropy source.
   *
   * @param clock time source; UTC-based clocks are expected
   */
  public DefaultUuidV7Generator(Clock clock) {
    this(clock, new SecureRandom());
  }

  /**
   * Creates a generator over the given clock and random source. The random-source overload exists
   * for deterministic tests (a seeded {@link RandomGenerator}).
   *
   * @param clock time source; UTC-based clocks are expected
   * @param random entropy source for the 62-bit {@code rand_b} field
   */
  public DefaultUuidV7Generator(Clock clock, RandomGenerator random) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public UUID generate() {
    long millis;
    int seq;
    lock.lock();
    try {
      long now = clock.millis();
      if (now > lastMillis) {
        lastMillis = now;
        counter = 0;
      } else {
        // Same millisecond, or a clock that ran backwards: hold time at lastMillis and advance the
        // monotonic counter so emitted values never decrease. On the 4096-per-ms overflow, carry
        // into the next millisecond.
        counter++;
        if (counter > COUNTER_MASK) {
          lastMillis++;
          counter = 0;
        }
      }
      millis = lastMillis;
      seq = counter;
    } finally {
      lock.unlock();
    }

    long msb = ((millis & MILLIS_MASK) << 16) | VERSION_7 | (seq & COUNTER_MASK);
    long lsb = VARIANT_IETF | (random.nextLong() & RAND_B_MASK);
    return new UUID(msb, lsb);
  }
}
