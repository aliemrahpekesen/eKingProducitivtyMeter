/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.tenancy.context.TenantContextHolder.NoTenantBoundException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextHolderTest {

  private static final TenantContext TENANT =
      TenantContext.of(UUID.fromString("018f0000-0000-7000-8000-0000000000b2"));

  @AfterEach
  void cleanup() {
    TenantContextHolder.clear();
  }

  @Test
  void currentIsEmptyWhenNothingBound() {
    assertThat(TenantContextHolder.current()).isEmpty();
  }

  @Test
  void setThenCurrentReturnsTheBoundTenant() {
    TenantContextHolder.set(TENANT);

    assertThat(TenantContextHolder.current()).contains(TENANT);
    assertThat(TenantContextHolder.require()).isEqualTo(TENANT);
  }

  @Test
  void clearRemovesTheBoundTenant() {
    TenantContextHolder.set(TENANT);

    TenantContextHolder.clear();

    assertThat(TenantContextHolder.current()).isEmpty();
  }

  @Test
  void requireFailsClosedWhenNothingBound() {
    assertThatThrownBy(TenantContextHolder::require)
        .isInstanceOf(NoTenantBoundException.class)
        .hasMessageContaining("no tenant bound");
  }
}
