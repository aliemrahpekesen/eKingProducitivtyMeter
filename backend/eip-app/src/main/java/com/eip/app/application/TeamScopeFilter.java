/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.security.EipPrincipal;
import com.eip.app.security.EipPrincipalHolder;
import com.eip.core.error.PermissionDeniedException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resource-level team-scope enforcement (SecurityModel §4 layer 2, DEBT-012 residual, part 2): the
 * shared filtering primitive the {@code TeamScoped*Query} decorators apply to every {@code
 * Team*View} list before it leaves the composition layer, and the current request's scope itself.
 *
 * <p>Reads {@link EipPrincipal#scopedTeamIds()} from {@link EipPrincipalHolder} — empty means
 * unrestricted (every existing caller's behavior, unchanged); non-empty restricts to those teams
 * only. {@link EipPrincipalHolder#current()} can be empty when no principal is bound at all (should
 * not happen on an authenticated {@code /api/v1} request past {@code EipPrincipalFilter}, but this
 * fails closed to "unrestricted" rather than throwing, matching {@link
 * com.eip.app.security.PermissionEnforcementInterceptor}'s own {@code EipPrincipal::anonymous}
 * fallback pattern) — RBAC's permission check, not this class, is what would already have rejected
 * an unauthenticated caller before reaching here.
 */
public final class TeamScopeFilter {

  private TeamScopeFilter() {}

  /**
   * Returns the current request's scoped team ids.
   *
   * @return the scope; empty means unrestricted
   */
  public static Set<UUID> currentScope() {
    return EipPrincipalHolder.current().map(EipPrincipal::scopedTeamIds).orElse(Set.of());
  }

  /**
   * Filters a {@code Team*View} list down to the current scope, if any. Returns the input list
   * unchanged (same reference) when the scope is empty (unrestricted) or every item already passes,
   * so an unrestricted caller never pays for a defensive copy.
   *
   * @param items the full, unfiltered list
   * @param teamIdOf extracts a row's team id
   * @param <T> the view type
   * @return the scope-filtered list
   */
  public static <T> List<T> restrictedTo(List<T> items, Function<T, UUID> teamIdOf) {
    Set<UUID> scope = currentScope();
    if (scope.isEmpty()) {
      return items;
    }
    List<T> filtered = items.stream().filter(item -> scope.contains(teamIdOf.apply(item))).toList();
    return filtered.size() == items.size() ? items : filtered;
  }

  /**
   * Guards a single-team drill-down (e.g. friction evidence) against the current scope.
   *
   * @param teamId the requested team
   * @throws PermissionDeniedException if the caller is scoped and {@code teamId} is outside it
   */
  public static void requireInScope(UUID teamId) {
    Set<UUID> scope = currentScope();
    if (!scope.isEmpty() && !scope.contains(teamId)) {
      throw new PermissionDeniedException(
          "team " + teamId + " is outside the caller's scoped teams");
    }
  }
}
