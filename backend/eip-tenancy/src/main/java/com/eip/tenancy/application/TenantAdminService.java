/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.application;

import com.eip.core.error.ValidationException;
import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.persistence.TenantAdminRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Platform-level tenant administration over {@code core.tenant} (no RLS by design). */
@Service
public class TenantAdminService implements ManageTenantsUseCase {

  private final TenantAdminRepository repository;

  public TenantAdminService(TenantAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public TenantView create(String name, String slug) {
    String normalized = slug.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9][a-z0-9-]{1,62}")) {
      throw new ValidationException(
          "slug must be 2-63 chars of lowercase letters, digits and dashes");
    }
    if (repository.slugExists(normalized)) {
      throw new ValidationException("a tenant with slug '" + normalized + "' already exists");
    }
    return repository.insert(name.trim(), normalized);
  }

  @Override
  public List<TenantView> list() {
    return repository.list();
  }
}
