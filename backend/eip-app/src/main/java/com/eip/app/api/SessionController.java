/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.tenant.TenantScopedJdbc;
import com.eip.tenancy.context.TenantContextHolder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the current session's tenant identity — the first tenant-aware endpoint. */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

  private final TenantScopedJdbc jdbc;

  public SessionController(TenantScopedJdbc jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Returns the current session's tenant identity.
   *
   * @return the resolved tenant and its organisation name (read under RLS)
   */
  @GetMapping("/session")
  public SessionView session() {
    UUID tenantId = TenantContextHolder.require().tenantId();
    Optional<String> organizationName =
        jdbc.read(
            client ->
                client
                    .sql(
                        "SELECT name FROM core.organization "
                            + "WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1")
                    .query(String.class)
                    .optional());
    return new SessionView(tenantId, organizationName.orElse(null));
  }
}
