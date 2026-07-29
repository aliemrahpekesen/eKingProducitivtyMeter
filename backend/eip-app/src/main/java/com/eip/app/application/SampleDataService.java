/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.tenancy.api.ManageOrgStructureUseCase;
import com.eip.tenancy.api.ManageOrgStructureUseCase.BusinessUnitView;
import com.eip.tenancy.api.ManageOrgStructureUseCase.OrganizationView;
import com.eip.tenancy.context.TenantContextHolder;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Composition-root sample-data loader: creates the minimal structure the simulation dataset maps
 * onto (an organisation, a business unit, and the Platform/Payments/Web teams) when missing, then
 * runs the friction pipeline for the current tenant. Idempotent — existing structure is reused and
 * the pipeline's replays converge.
 */
@Service
public class SampleDataService implements LoadSampleDataUseCase {

  private static final List<String> SAMPLE_TEAMS = List.of("Platform", "Payments", "Web");

  private final ManageOrgStructureUseCase structure;
  private final RunFrictionPipelineUseCase pipeline;

  public SampleDataService(
      ManageOrgStructureUseCase structure, RunFrictionPipelineUseCase pipeline) {
    this.structure = structure;
    this.pipeline = pipeline;
  }

  @Override
  public RunFrictionPipelineUseCase.PipelineResult load() {
    UUID tenantId = TenantContextHolder.require().tenantId();

    List<OrganizationView> organizations = structure.structure();
    UUID organizationId =
        organizations.isEmpty()
            ? structure.createOrganization("Sample Org", "sample-org")
            : organizations.get(0).id();

    UUID businessUnitId =
        organizations.stream()
            .flatMap(o -> o.businessUnits().stream())
            .findFirst()
            .map(BusinessUnitView::id)
            .orElseGet(() -> structure.createBusinessUnit(organizationId, "Sample Engineering"));

    Set<String> existingTeams =
        organizations.stream()
            .flatMap(o -> o.businessUnits().stream())
            .flatMap(bu -> bu.teams().stream())
            .map(t -> t.name())
            .collect(Collectors.toSet());
    for (String team : SAMPLE_TEAMS) {
      if (!existingTeams.contains(team)) {
        structure.createTeam(businessUnitId, team);
      }
    }

    return pipeline.run(tenantId);
  }
}
