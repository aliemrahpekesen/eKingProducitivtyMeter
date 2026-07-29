/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.context;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RlsTenantBinderTest {

  @Test
  void bind_setsTransactionLocalGuc_withTheTenantId() throws Exception {
    // given
    UUID tenantId = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(connection.prepareStatement(RlsTenantBinder.SET_TENANT_SQL)).thenReturn(statement);

    // when
    RlsTenantBinder.bind(connection, TenantContext.of(tenantId));

    // then — parameterized, transaction-local set_config; executed and closed
    verify(connection).prepareStatement("select set_config('app.tenant_id', ?, true)");
    verify(statement).setString(1, tenantId.toString());
    verify(statement).execute();
    verify(statement).close();
  }

  @Test
  @SuppressWarnings("NullAway") // deliberately passes null to verify the non-null contract
  void bind_rejectsNullArguments() {
    Connection connection = mock(Connection.class);
    assertThatNullPointerException()
        .isThrownBy(() -> RlsTenantBinder.bind(null, TenantContext.of(UUID.randomUUID())));
    assertThatNullPointerException().isThrownBy(() -> RlsTenantBinder.bind(connection, null));
  }
}
