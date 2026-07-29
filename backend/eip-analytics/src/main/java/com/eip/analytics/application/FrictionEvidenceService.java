/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.application;

import com.eip.analytics.api.FrictionEvidenceView;
import com.eip.analytics.api.FrictionEvidenceView.TransitionEvidenceView;
import com.eip.analytics.api.FrictionEvidenceView.WorkItemEvidenceView;
import com.eip.analytics.api.GetFrictionEvidenceQuery;
import com.eip.analytics.friction.FrictionDefinition;
import com.eip.analytics.persistence.FrictionReadRepository;
import com.eip.analytics.persistence.FrictionReadRepository.EvidenceItemRow;
import com.eip.analytics.persistence.FrictionReadRepository.EvidenceTransitionRow;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles a team's friction drill-to-evidence from the correlation store and the canonical
 * artifacts it stitched. Three bounded queries in one tenant-bound read-only transaction (team,
 * items+artifacts, all transitions) — no per-item loops. Evidence identifies artifacts, never
 * individuals (NFR-071).
 */
@Service
public class FrictionEvidenceService implements GetFrictionEvidenceQuery {

  private final TenantTransactionRunner tx;
  private final FrictionReadRepository repository;

  public FrictionEvidenceService(TenantTransactionRunner tx, FrictionReadRepository repository) {
    this.tx = tx;
    this.repository = repository;
  }

  @Override
  public FrictionEvidenceView evidence(UUID teamId) {
    return tx.readCurrent(
        () -> {
          Map<UUID, List<TransitionEvidenceView>> transitions = new HashMap<>();
          for (EvidenceTransitionRow row : repository.evidenceTransitions(teamId)) {
            transitions
                .computeIfAbsent(row.workItemId(), id -> new ArrayList<>())
                .add(row.transition());
          }
          List<WorkItemEvidenceView> items =
              repository.evidenceItems(teamId).stream()
                  .map(row -> toView(row, transitions.getOrDefault(row.workItemId(), List.of())))
                  .toList();
          return new FrictionEvidenceView(
              teamId, repository.teamName(teamId).orElse(null), FrictionDefinition.VERSION, items);
        });
  }

  private static WorkItemEvidenceView toView(
      EvidenceItemRow row, List<TransitionEvidenceView> transitions) {
    return new WorkItemEvidenceView(
        row.workItemKey(),
        row.title(),
        row.type(),
        row.status(),
        row.cycleTimeSec(),
        row.activeSec(),
        row.blockedSec(),
        row.reviewWaitSec(),
        row.waitingSec(),
        row.reworkCount(),
        row.pullRequestKey(),
        row.buildKey(),
        row.buildStatus(),
        row.qualityGateKey(),
        row.qualityGateStatus(),
        transitions);
  }
}
