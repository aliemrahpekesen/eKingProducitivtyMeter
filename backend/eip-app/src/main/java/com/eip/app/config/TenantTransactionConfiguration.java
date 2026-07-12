/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.tenancy.tx.TenantTransactionRunner;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Composition-root wiring for the tenant-aware transaction boundary: one {@link
 * TenantTransactionRunner} over Boot's auto-configured transaction manager and datasource, injected
 * into every application service and repository that touches tenant data.
 */
@Configuration(proxyBeanMethods = false)
public class TenantTransactionConfiguration {

  /**
   * The single tenant-aware transaction runner.
   *
   * @param transactionManager Boot's JDBC transaction manager
   * @param dataSource the RLS-enforced application datasource
   * @return the runner
   */
  @Bean
  public TenantTransactionRunner tenantTransactionRunner(
      PlatformTransactionManager transactionManager, DataSource dataSource) {
    return new TenantTransactionRunner(transactionManager, dataSource);
  }
}
