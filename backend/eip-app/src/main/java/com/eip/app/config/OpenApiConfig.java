/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Names the generated OpenAPI 3 contract for the {@code /api/v1} surface. Deterministic metadata
 * (fixed title/version) so the committed contract snapshot is stable across builds — no host, date,
 * or build-number drift.
 */
@Configuration
public class OpenApiConfig {

  /**
   * Provides the OpenAPI document metadata.
   *
   * @return the OpenAPI definition with EIP title and the {@code v1} API version
   */
  @Bean
  public OpenAPI eipOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Engineering Intelligence Platform API")
                .version("v1")
                .description(
                    "REST /api/v1 surface. Team-level engineering intelligence; "
                        + "no individual developer metrics (NFR-071)."));
  }
}
