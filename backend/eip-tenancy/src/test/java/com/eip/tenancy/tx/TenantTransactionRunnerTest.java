/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Proves the runner's contract over mocked seams: the GUC bind statement runs on the connection
 * obtained inside the active transaction, read-only work uses a read-only definition, exceptions
 * roll back (never commit), bind failures fail loud, and no-current-tenant fails closed. The
 * real-database proof (same-connection GUC visibility, rollback, isolation, no pool leakage) is
 * {@code TenantTransactionRunnerIntegrationTest} in eip-app.
 */
class TenantTransactionRunnerTest {

  private static final TenantContext TENANT =
      TenantContext.of(UUID.fromString("00000000-0000-4000-8000-0000000000aa"));

  private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
  private final DataSource dataSource = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);
  private final PreparedStatement statement = mock(PreparedStatement.class);

  private final TenantTransactionRunner runner = new TenantTransactionRunner(txManager, dataSource);

  @BeforeEach
  void wire() throws SQLException {
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(any())).thenReturn(statement);
  }

  @AfterEach
  void clearHolder() {
    TenantContextHolder.clear();
  }

  @Test
  void call_binds_the_tenant_guc_inside_the_transaction_and_commits() throws SQLException {
    String result = runner.call(TENANT, () -> "ok");

    assertThat(result).isEqualTo("ok");
    verify(connection).prepareStatement("select set_config('app.tenant_id', ?, true)");
    verify(statement).setString(1, TENANT.tenantId().toString());
    verify(statement).execute();
    verify(txManager).commit(any());
    verify(txManager, never()).rollback(any());
  }

  @Test
  void read_uses_a_read_only_transaction_definition() {
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);

    runner.read(TENANT, () -> "r");

    verify(txManager).getTransaction(definition.capture());
    assertThat(definition.getValue().isReadOnly()).isTrue();
  }

  @Test
  void failing_work_rolls_back_and_propagates() {
    assertThatThrownBy(
            () ->
                runner.run(
                    TENANT,
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
    verify(txManager).rollback(any());
    verify(txManager, never()).commit(any());
  }

  @Test
  void a_bind_failure_fails_loud_and_rolls_back() throws SQLException {
    when(statement.execute()).thenThrow(new SQLException("no db"));

    assertThatThrownBy(() -> runner.call(TENANT, () -> "unreachable"))
        .isInstanceOf(DataAccessResourceFailureException.class);
    verify(txManager).rollback(any());
  }

  @Test
  void null_work_results_are_rejected() {
    assertThatThrownBy(() -> runner.call(TENANT, () -> null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("transactional work returned null");
  }

  @Test
  void readCurrent_fails_closed_without_a_bound_tenant() {
    assertThatThrownBy(() -> runner.readCurrent(() -> "x"))
        .isInstanceOf(TenantContextHolder.NoTenantBoundException.class);
    verify(txManager, never()).getTransaction(any());
  }

  @Test
  void readCurrent_uses_the_thread_bound_tenant() throws SQLException {
    TenantContextHolder.set(TENANT);

    assertThat(runner.readCurrent(() -> "y")).isEqualTo("y");
    verify(statement).setString(eq(1), eq(TENANT.tenantId().toString()));
  }
}
