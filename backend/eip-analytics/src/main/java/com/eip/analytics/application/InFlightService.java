/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.application;

import com.eip.analytics.api.GetInFlightWorkQuery;
import com.eip.analytics.api.InFlightItemView;
import com.eip.analytics.api.TeamInFlightView;
import com.eip.analytics.persistence.InFlightRepository;
import com.eip.analytics.persistence.InFlightRepository.ItemRow;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles the in-flight (unresolved) work view, grouped by team with the oldest items first. One
 * tenant-bound read-only transaction over the repository's single bounded join; grouping and
 * sorting run in Java. Team-level only — identifies work items, never assignees (Law 6 / NFR-071).
 */
@Service
public class InFlightService implements GetInFlightWorkQuery {

  private static final Comparator<TeamInFlightView> TEAM_ORDER =
      Comparator.comparing(TeamInFlightView::teamName).thenComparing(t -> t.teamId().toString());

  private final TenantTransactionRunner tx;
  private final InFlightRepository repository;

  /**
   * Creates the service.
   *
   * @param tx the tenant-bound transaction runner
   * @param repository the in-flight read repository
   */
  public InFlightService(TenantTransactionRunner tx, InFlightRepository repository) {
    this.tx = tx;
    this.repository = repository;
  }

  @Override
  public List<TeamInFlightView> inFlight() {
    return tx.readCurrent(this::build);
  }

  private List<TeamInFlightView> build() {
    Map<UUID, String> teamNames = new LinkedHashMap<>();
    Map<UUID, List<InFlightItemView>> byTeam = new LinkedHashMap<>();
    for (ItemRow row : repository.items()) {
      teamNames.putIfAbsent(row.teamId(), row.teamName());
      byTeam
          .computeIfAbsent(row.teamId(), id -> new ArrayList<>())
          .add(
              new InFlightItemView(
                  row.workItemKey(), row.title(), row.state(), row.ageSec(), row.blocked()));
    }

    List<TeamInFlightView> teams = new ArrayList<>(byTeam.size());
    byTeam.forEach(
        (teamId, items) -> {
          String teamName = Objects.requireNonNull(teamNames.get(teamId));
          teams.add(new TeamInFlightView(teamId, teamName, items));
        });
    teams.sort(TEAM_ORDER);
    return teams;
  }
}
