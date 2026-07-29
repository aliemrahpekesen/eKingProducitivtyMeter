/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.tenancy.audit.api.RecordAuditEventUseCase;
import com.eip.tenancy.secrets.SecretsService;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Composition-root wiring for envelope-encrypted secret storage (ADR-014). */
@Configuration(proxyBeanMethods = false)
public class SecretsConfiguration {

  /**
   * The tenant-scoped secrets service.
   *
   * @param jdbc the shared JDBC client (joins tenant-bound transactions)
   * @param properties validated key material configuration
   * @param audit the audit write path (DEBT-024 Wave 3B: {@code secret.revealed} on every reveal)
   * @return the service
   */
  @Bean
  public SecretsService secretsService(
      JdbcClient jdbc, EipSecretsProperties properties, RecordAuditEventUseCase audit) {
    return new SecretsService(
        jdbc, Base64.getDecoder().decode(properties.masterKey()), properties.kekVersion(), audit);
  }
}
