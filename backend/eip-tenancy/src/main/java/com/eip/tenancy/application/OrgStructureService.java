/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.application;

import com.eip.tenancy.api.ManageOrgStructureUseCase;
import com.eip.tenancy.persistence.OrgStructureRepository;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Tenant-scoped organisation-structure administration, one tenant-bound transaction per call. */
@Service
public class OrgStructureService implements ManageOrgStructureUseCase {

  private final TenantTransactionRunner tx;
  private final OrgStructureRepository repository;

  public OrgStructureService(TenantTransactionRunner tx, OrgStructureRepository repository) {
    this.tx = tx;
    this.repository = repository;
  }

  @Override
  public List<OrganizationView> structure() {
    return tx.readCurrent(repository::structure);
  }

  @Override
  public UUID createOrganization(String name, String slug) {
    return tx.callCurrent(() -> repository.insertOrganization(name.trim(), slug.trim()));
  }

  @Override
  public UUID createBusinessUnit(UUID organizationId, String name) {
    return tx.callCurrent(() -> repository.insertBusinessUnit(organizationId, name.trim()));
  }

  @Override
  public UUID createTeam(UUID businessUnitId, String name) {
    return tx.callCurrent(() -> repository.insertTeam(businessUnitId, name.trim()));
  }
}
