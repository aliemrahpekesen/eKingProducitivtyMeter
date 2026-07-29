/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.app.security.EipPrincipal;
import com.eip.app.security.EipPrincipalHolder;
import com.eip.core.error.PermissionDeniedException;
import com.eip.tenancy.rbac.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit-tests {@link TeamScopeFilter} directly against {@link EipPrincipalHolder}. */
class TeamScopeFilterTest {

  private record Row(UUID teamId, String label) {}

  @AfterEach
  void clearPrincipal() {
    EipPrincipalHolder.clear();
  }

  @Test
  void no_principal_bound_is_unrestricted() {
    assertThat(TeamScopeFilter.currentScope()).isEmpty();
  }

  @Test
  void empty_scoped_team_ids_is_unrestricted_and_returns_the_same_list_instance() {
    bindPrincipal(Set.of());
    List<Row> rows = List.of(new Row(UUID.randomUUID(), "a"), new Row(UUID.randomUUID(), "b"));

    List<Row> result = TeamScopeFilter.restrictedTo(rows, Row::teamId);

    assertThat(result).isSameAs(rows);
  }

  @Test
  void non_empty_scope_filters_to_only_the_scoped_teams() {
    UUID teamA = UUID.randomUUID();
    UUID teamB = UUID.randomUUID();
    bindPrincipal(Set.of(teamA));
    List<Row> rows = List.of(new Row(teamA, "a"), new Row(teamB, "b"));

    List<Row> result = TeamScopeFilter.restrictedTo(rows, Row::teamId);

    assertThat(result).extracting(Row::teamId).containsExactly(teamA);
  }

  @Test
  void requireInScope_passes_silently_when_unrestricted() {
    bindPrincipal(Set.of());
    TeamScopeFilter.requireInScope(UUID.randomUUID()); // must not throw
  }

  @Test
  void requireInScope_passes_for_a_team_inside_the_scope() {
    UUID teamA = UUID.randomUUID();
    bindPrincipal(Set.of(teamA));
    TeamScopeFilter.requireInScope(teamA); // must not throw
  }

  @Test
  void requireInScope_rejects_a_team_outside_the_scope() {
    UUID teamA = UUID.randomUUID();
    UUID teamB = UUID.randomUUID();
    bindPrincipal(Set.of(teamA));

    assertThatThrownBy(() -> TeamScopeFilter.requireInScope(teamB))
        .isInstanceOf(PermissionDeniedException.class);
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
}
