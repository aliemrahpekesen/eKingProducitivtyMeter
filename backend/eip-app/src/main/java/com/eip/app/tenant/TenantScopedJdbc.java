/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContextHolder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a read on the tenant's data with Row-Level Security active. Within a single transaction it
 * binds the current thread's tenant onto the transaction's connection (the RLS GUC, {@link
 * RlsTenantBinder}) and then executes the query on that same connection, so the database enforces
 * isolation. If no tenant is bound the read fails closed ({@link TenantContextHolder#require()}).
 */
@Component
public class TenantScopedJdbc {

  private final DataSource dataSource;
  private final JdbcClient jdbcClient;

  public TenantScopedJdbc(DataSource dataSource) {
    this.dataSource = dataSource;
    this.jdbcClient = JdbcClient.create(dataSource);
  }

  /**
   * Executes {@code work} inside a read-only transaction with the current tenant's RLS GUC set.
   *
   * @param work the query, given a tenant-scoped {@link JdbcClient}
   * @param <T> the result type
   * @return the query result
   */
  @Transactional(readOnly = true)
  public <T> T read(Function<JdbcClient, T> work) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      RlsTenantBinder.bind(connection, TenantContextHolder.require());
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("failed to bind tenant for RLS", e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
    return work.apply(jdbcClient);
  }
}
