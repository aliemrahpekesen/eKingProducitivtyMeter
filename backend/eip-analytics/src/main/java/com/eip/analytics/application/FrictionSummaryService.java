/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.application;

import com.eip.analytics.api.FrictionMetricView;
import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.GetFrictionSummaryQuery;
import com.eip.analytics.api.TeamFrictionView;
import com.eip.analytics.persistence.FrictionReadRepository;
import com.eip.analytics.persistence.FrictionReadRepository.SummaryRow;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Assembles the Engineering Friction summary from the RLS-protected computed read model — not from
 * seed rows. One tenant-bound read-only transaction; teams ordered worst-first with a stable
 * tie-break. Team-level only (Law 6 / NFR-071).
 */
@Service
public class FrictionSummaryService implements GetFrictionSummaryQuery {

  /** Worst-first, then a stable tie-break by name then id — a deterministic total order. */
  private static final Comparator<TeamFrictionView> WORST_FIRST =
      Comparator.comparingInt(TeamFrictionView::frictionScore)
          .reversed()
          .thenComparing(TeamFrictionView::teamName)
          .thenComparing(t -> t.teamId().toString());

  private final TenantTransactionRunner tx;
  private final FrictionReadRepository repository;

  public FrictionSummaryService(TenantTransactionRunner tx, FrictionReadRepository repository) {
    this.tx = tx;
    this.repository = repository;
  }

  @Override
  public FrictionSummaryView summary() {
    return tx.readCurrent(
        () -> {
          Optional<FrictionMetricView> metric = repository.definition();
          List<SummaryRow> rows = repository.summaryRows();
          List<TeamFrictionView> teams =
              rows.stream().map(SummaryRow::view).sorted(WORST_FIRST).toList();
          String version = rows.stream().map(SummaryRow::version).findFirst().orElse(null);
          String computedAt =
              rows.stream()
                  .map(SummaryRow::computedAt)
                  .filter(Objects::nonNull)
                  .max(Comparator.naturalOrder())
                  .map(Instant::toString)
                  .orElse(null);
          return new FrictionSummaryView(
              metric.orElse(null), version, computedAt, true, teams, teams.size());
        });
  }
}
