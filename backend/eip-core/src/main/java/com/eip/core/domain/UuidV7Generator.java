/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

import java.util.UUID;

/**
 * Generates the platform's canonical entity identifiers as time-ordered UUIDv7 values (DomainModel
 * §2.1; BackendPlan §2.5). UUIDv7 gives index- and cursor-pagination-friendly keys without a
 * central sequence.
 *
 * <p>Every entity id in EIP is minted through this kernel generator so ordering and encoding are
 * uniform across modules. Implementations MUST be safe for concurrent use and MUST produce values
 * whose byte order is non-decreasing in wall-clock time.
 */
@FunctionalInterface
public interface UuidV7Generator {

  /**
   * Returns a fresh UUIDv7. Successive calls at non-decreasing clock instants return values that
   * sort non-decreasing by their unsigned 128-bit byte order.
   *
   * @return a version-7, IETF-variant UUID
   */
  UUID generate();
}
