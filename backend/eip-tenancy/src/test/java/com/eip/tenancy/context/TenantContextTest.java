/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantContextTest {

  @Test
  void holdsTheTenantId() {
    UUID id = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    assertThat(TenantContext.of(id).tenantId()).isEqualTo(id);
    assertThat(new TenantContext(id))
        .isEqualTo(TenantContext.of(id))
        .hasSameHashCodeAs(new TenantContext(id));
    assertThat(TenantContext.of(id)).hasToString(new TenantContext(id).toString());
  }

  @Test
  @SuppressWarnings("NullAway") // deliberately passes null to verify the non-null contract
  void rejectsNullTenantId() {
    assertThatNullPointerException().isThrownBy(() -> new TenantContext(null));
  }
}
