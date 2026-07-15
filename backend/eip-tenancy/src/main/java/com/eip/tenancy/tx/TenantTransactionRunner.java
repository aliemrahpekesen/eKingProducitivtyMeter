/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.tx;

import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work inside one Spring-managed local transaction with the tenant's RLS GUC bound
 * to that transaction's connection. This is the single approved transaction boundary: it opens the
 * transaction ({@link TransactionTemplate}), binds {@code app.tenant_id} via {@link
 * RlsTenantBinder} on the transaction-bound connection (obtained through {@link DataSourceUtils},
 * so every subsequent {@code JdbcClient}/{@code JdbcTemplate} call inside the work joins the same
 * connection), and lets Spring commit or roll back. The GUC is transaction-local ({@code
 * set_config(..., true)}), so it resets on commit/rollback and never leaks into the pool — no
 * manual autocommit or state restoration anywhere.
 *
 * <p>Local PostgreSQL transactions only (BackendPlan §6): no XA/JTA. Callers doing external IO
 * (connector fetches) must do it OUTSIDE the supplied work so no remote call holds a transaction
 * open.
 */
public class TenantTransactionRunner {

  private final TransactionTemplate readWrite;
  private final TransactionTemplate readOnly;
  private final DataSource dataSource;

  /**
   * Creates the runner over the application's transaction manager and datasource.
   *
   * @param transactionManager the Spring transaction manager
   * @param dataSource the datasource whose transaction-bound connection carries the GUC
   */
  public TenantTransactionRunner(
      PlatformTransactionManager transactionManager, DataSource dataSource) {
    this.readWrite = new TransactionTemplate(transactionManager);
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
    this.dataSource = dataSource;
  }

  /**
   * Runs read-write work in a tenant-bound transaction and returns its result.
   *
   * @param tenant the tenant to bind
   * @param work the transactional work (must not return null)
   * @param <T> the result type
   * @return the work's result
   */
  public <T> T call(TenantContext tenant, Supplier<T> work) {
    return execute(readWrite, tenant, work);
  }

  /**
   * Runs read-write work with no result in a tenant-bound transaction.
   *
   * @param tenant the tenant to bind
   * @param work the transactional work
   */
  public void run(TenantContext tenant, Runnable work) {
    execute(
        readWrite,
        tenant,
        () -> {
          work.run();
          return Boolean.TRUE;
        });
  }

  /**
   * Runs read-only work in a tenant-bound transaction and returns its result.
   *
   * @param tenant the tenant to bind
   * @param work the transactional work (must not return null)
   * @param <T> the result type
   * @return the work's result
   */
  public <T> T read(TenantContext tenant, Supplier<T> work) {
    return execute(readOnly, tenant, work);
  }

  /**
   * Runs read-only work for the current thread's tenant, failing closed if none is bound.
   *
   * @param work the transactional work (must not return null)
   * @param <T> the result type
   * @return the work's result
   */
  public <T> T readCurrent(Supplier<T> work) {
    return read(TenantContextHolder.require(), work);
  }

  /**
   * Runs read-write work for the current thread's tenant, failing closed if none is bound.
   *
   * @param work the transactional work (must not return null)
   * @param <T> the result type
   * @return the work's result
   */
  public <T> T callCurrent(Supplier<T> work) {
    return call(TenantContextHolder.require(), work);
  }

  private <T> T execute(TransactionTemplate template, TenantContext tenant, Supplier<T> work) {
    T result =
        template.execute(
            status -> {
              bind(tenant);
              return work.get();
            });
    return Objects.requireNonNull(result, "transactional work returned null");
  }

  /** Binds the RLS GUC on the transaction-bound connection (inside the active transaction). */
  private void bind(TenantContext tenant) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      RlsTenantBinder.bind(connection, tenant);
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("failed to bind tenant for RLS", e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
