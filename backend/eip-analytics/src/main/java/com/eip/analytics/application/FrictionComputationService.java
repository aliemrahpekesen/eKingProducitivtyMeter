/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.application;

import com.eip.analytics.api.ComputeFrictionUseCase;
import com.eip.analytics.friction.FlowMetrics;
import com.eip.analytics.friction.FlowTimeline;
import com.eip.analytics.friction.FrictionCalculator;
import com.eip.analytics.friction.TeamFriction;
import com.eip.analytics.friction.TimelineStage;
import com.eip.analytics.persistence.FrictionProjectionRepository;
import com.eip.analytics.persistence.FrictionProjectionRepository.CorrelationUpsert;
import com.eip.analytics.persistence.FrictionProjectionRepository.ResolvedItem;
import com.eip.analytics.persistence.FrictionProjectionRepository.TransitionRow;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computes Engineering Friction v0.1: loads the correlated canonical flow with bounded set-based
 * queries (one per entity kind — no N+1), decomposes each work item's transition timeline with the
 * pure {@link FlowTimeline} engine, aggregates per team with {@link FrictionCalculator}, and
 * persists evidence + read model + {@code metric_fact} in batch upserts, all inside one
 * tenant-bound transaction. Deterministic and idempotent by construction.
 */
@Service
public class FrictionComputationService implements ComputeFrictionUseCase {

  private final TenantTransactionRunner tx;
  private final FrictionProjectionRepository repository;

  public FrictionComputationService(
      TenantTransactionRunner tx, FrictionProjectionRepository repository) {
    this.tx = tx;
    this.repository = repository;
  }

  @Override
  public FrictionComputation compute(TenantContext tenant) {
    return tx.call(
        tenant,
        () -> {
          UUID metricId = repository.upsertDefinition();
          Optional<Timestamp> bucketAt = repository.latestResolvedAt();
          if (bucketAt.isEmpty()) {
            return new FrictionComputation(0, 0); // no resolved work items yet
          }

          List<ResolvedItem> items = repository.resolvedItems();
          Map<UUID, List<TransitionRow>> transitionsByItem = new HashMap<>();
          for (TransitionRow row : repository.transitionsForResolvedItems()) {
            transitionsByItem.computeIfAbsent(row.workItemId(), id -> new ArrayList<>()).add(row);
          }
          Map<UUID, UUID> prByItem = repository.firstPullRequestByWorkItem();
          Map<UUID, UUID> buildByPr = repository.firstBuildByPullRequest();
          Map<UUID, UUID> gateByBuild = repository.firstGateByBuild();

          List<CorrelationUpsert> correlations = new ArrayList<>(items.size());
          Map<UUID, List<FlowMetrics>> byTeam = new LinkedHashMap<>();
          for (ResolvedItem item : items) {
            FlowMetrics metrics =
                FlowTimeline.of(
                    stagesOf(item, transitionsByItem.getOrDefault(item.id(), List.of())));
            UUID prId = prByItem.get(item.id());
            UUID buildId = prId == null ? null : buildByPr.get(prId);
            UUID gateId = buildId == null ? null : gateByBuild.get(buildId);
            correlations.add(
                new CorrelationUpsert(item.teamId(), item.id(), prId, buildId, gateId, metrics));
            byTeam.computeIfAbsent(item.teamId(), id -> new ArrayList<>()).add(metrics);
          }

          List<TeamFriction> teams = new ArrayList<>(byTeam.size());
          byTeam.forEach((teamId, metrics) -> teams.add(FrictionCalculator.of(teamId, metrics)));

          repository.upsertCorrelations(correlations);
          repository.upsertReadModel(teams);
          repository.upsertFacts(metricId, bucketAt.get(), teams);
          return new FrictionComputation(teams.size(), items.size());
        });
  }

  /**
   * Reconstructs the item's stage timeline: the created stage first (the first transition's
   * from-state, {@code TODO} when absent), then each transition's target state; items with no
   * recorded transitions decompose as created→resolved.
   */
  private static List<TimelineStage> stagesOf(ResolvedItem item, List<TransitionRow> transitions) {
    List<TimelineStage> stages = new ArrayList<>(transitions.size() + 1);
    if (transitions.isEmpty()) {
      stages.add(new TimelineStage("TODO", item.createdSec()));
      stages.add(new TimelineStage("DONE", item.resolvedSec()));
      return stages;
    }
    TransitionRow first = transitions.get(0);
    stages.add(
        new TimelineStage(
            first.fromState() == null ? "TODO" : first.fromState(), item.createdSec()));
    for (TransitionRow row : transitions) {
      stages.add(new TimelineStage(row.toState(), row.atSec()));
    }
    return stages;
  }
}
