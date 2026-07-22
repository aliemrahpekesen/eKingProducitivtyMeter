/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.analytics.api.FrictionEvidenceView;
import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.GetFrictionEvidenceQuery;
import com.eip.analytics.api.GetFrictionSummaryQuery;
import com.eip.analytics.api.TeamFrictionView;
import com.eip.app.security.EipPrincipal;
import com.eip.app.security.EipPrincipalHolder;
import com.eip.core.error.PermissionDeniedException;
import com.eip.tenancy.rbac.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link TeamScopedFrictionSummaryQuery} and {@link TeamScopedFrictionEvidenceQuery}
 * against a hand-rolled fake delegate (TestingStrategy §2 — no mocking) — the two decorators with
 * logic beyond a plain {@link TeamScopeFilter#restrictedTo} pass-through (the {@code
 * teamsReporting} count correction, and the evidence single-team scope guard). Real bean-wiring
 * proof (the {@link org.springframework.context.annotation.Primary}/{@link
 * org.springframework.beans.factory.annotation.Qualifier} decoration actually taking effect over
 * {@code eip-analytics}' real beans) lives in {@code OidcRbacIntegrationTest}.
 */
class TeamScopedFrictionQueriesTest {

  private static final UUID TEAM_A = UUID.randomUUID();
  private static final UUID TEAM_B = UUID.randomUUID();

  private final FakeFrictionSummaryQuery fakeSummary =
      new FakeFrictionSummaryQuery(
          new FrictionSummaryView(
              null,
              "engineering_friction_v0.1",
              null,
              true,
              List.of(teamFriction(TEAM_A), teamFriction(TEAM_B)),
              2));
  private final TeamScopedFrictionSummaryQuery summaryQuery =
      new TeamScopedFrictionSummaryQuery(fakeSummary);

  @AfterEach
  void clearPrincipal() {
    EipPrincipalHolder.clear();
  }

  @Test
  void unrestricted_caller_sees_the_full_summary_unchanged() {
    bindPrincipal(Set.of());

    FrictionSummaryView result = summaryQuery.summary();

    assertThat(result.teams()).hasSize(2);
    assertThat(result.teamsReporting()).isEqualTo(2);
  }

  @Test
  void scoped_caller_sees_only_their_team_and_a_corrected_reporting_count() {
    bindPrincipal(Set.of(TEAM_A));

    FrictionSummaryView result = summaryQuery.summary();

    assertThat(result.teams()).extracting(TeamFrictionView::teamId).containsExactly(TEAM_A);
    // The count must reflect the FILTERED list, never leaking the true cross-team total.
    assertThat(result.teamsReporting()).isEqualTo(1);
  }

  @Test
  void evidence_out_of_scope_team_is_denied() {
    bindPrincipal(Set.of(TEAM_A));
    TeamScopedFrictionEvidenceQuery evidenceQuery =
        new TeamScopedFrictionEvidenceQuery(new FakeFrictionEvidenceQuery());

    assertThatThrownBy(() -> evidenceQuery.evidence(TEAM_B))
        .isInstanceOf(PermissionDeniedException.class);
  }

  @Test
  void evidence_in_scope_team_is_served() {
    bindPrincipal(Set.of(TEAM_A));
    TeamScopedFrictionEvidenceQuery evidenceQuery =
        new TeamScopedFrictionEvidenceQuery(new FakeFrictionEvidenceQuery());

    FrictionEvidenceView result = evidenceQuery.evidence(TEAM_A);

    assertThat(result.teamId()).isEqualTo(TEAM_A);
  }

  private static void bindPrincipal(Set<UUID> scopedTeamIds) {
    EipPrincipalHolder.set(
        new EipPrincipal(
            UUID.randomUUID(),
            Set.of(Role.ENGINEERING_MANAGER),
            Role.ENGINEERING_MANAGER.permissions(),
            "test-manager",
            scopedTeamIds));
  }

  private static TeamFrictionView teamFriction(UUID teamId) {
    return new TeamFrictionView(teamId, "Team " + teamId, 50, "NONE", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  private record FakeFrictionSummaryQuery(FrictionSummaryView fixed)
      implements GetFrictionSummaryQuery {
    @Override
    public FrictionSummaryView summary() {
      return fixed;
    }
  }

  private static final class FakeFrictionEvidenceQuery implements GetFrictionEvidenceQuery {
    @Override
    public FrictionEvidenceView evidence(UUID teamId) {
      return new FrictionEvidenceView(
          teamId, "Team " + teamId, "engineering_friction_v0.1", List.of());
    }
  }
}
