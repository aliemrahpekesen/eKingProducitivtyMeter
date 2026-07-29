/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.rbac;

import static com.eip.tenancy.rbac.Permission.AI_AGENT_INVOKE;
import static com.eip.tenancy.rbac.Permission.AI_POLICY_MANAGE;
import static com.eip.tenancy.rbac.Permission.AUDIT_READ;
import static com.eip.tenancy.rbac.Permission.CONNECTOR_CONFIGURE;
import static com.eip.tenancy.rbac.Permission.CONNECTOR_SECRET_WRITE;
import static com.eip.tenancy.rbac.Permission.DASHBOARD_VIEW;
import static com.eip.tenancy.rbac.Permission.PLATFORM_OPERATE;
import static com.eip.tenancy.rbac.Permission.REPORT_EXPORT;
import static com.eip.tenancy.rbac.Permission.REPORT_GENERATE;
import static com.eip.tenancy.rbac.Permission.TENANT_MANAGE;
import static com.eip.tenancy.rbac.Permission.USER_MANAGE;

import java.util.Set;

/**
 * The canonical role catalog (SecurityModel §4 role table + permission-catalog matrix, transcribed
 * exactly — {@code RbacMatrixTest} fails the build on any drift). Four base roles plus six seeded
 * persona templates (Personas.md §1, FEAT-004); {@link #permissions()} is a role's effective, fixed
 * v0.1 permission set (no per-tenant editing of the built-in matrix yet).
 */
public enum Role {

  /**
   * Deployment-wide operator (SecurityModel §4): tenant lifecycle, users, platform health/KMS.
   * Deliberately excludes every business-data permission — cross-tenant data access requires an
   * explicit, audited support-access grant, not this role.
   */
  PLATFORM_ADMIN(Set.of(TENANT_MANAGE, USER_MANAGE, AUDIT_READ, PLATFORM_OPERATE)),

  /**
   * Customer IT owner (SecurityModel §4): tenant config, connectors + secrets, users/roles,
   * dashboards, and reports. Deliberately EXCLUDES {@link Permission#CONNECTOR_SECRET_REVEAL} — the
   * SecurityModel §4 table marks it "opt-in, audited" rather than a default-granted permission, so
   * revealing a secret's plaintext requires a separate, explicitly granted (and always audited)
   * capability even for the tenant owner.
   */
  TENANT_ADMIN(
      Set.of(
          TENANT_MANAGE,
          USER_MANAGE,
          CONNECTOR_CONFIGURE,
          CONNECTOR_SECRET_WRITE,
          DASHBOARD_VIEW,
          REPORT_GENERATE,
          REPORT_EXPORT,
          AUDIT_READ,
          AI_AGENT_INVOKE,
          AI_POLICY_MANAGE)),

  /** Seeded template (manager-scope): EM — team-level metrics, reports for owned scope. */
  ENGINEERING_MANAGER(Set.of(DASHBOARD_VIEW, REPORT_GENERATE, REPORT_EXPORT, AI_AGENT_INVOKE)),

  /** Seeded template (manager-scope): team lead — team dashboards + generated sprint reviews. */
  TEAM_LEAD(Set.of(DASHBOARD_VIEW, REPORT_GENERATE, REPORT_EXPORT, AI_AGENT_INVOKE)),

  /** Seeded template (manager-scope): release/delivery manager — release dashboards + notes. */
  RELEASE_MANAGER(Set.of(DASHBOARD_VIEW, REPORT_GENERATE, REPORT_EXPORT, AI_AGENT_INVOKE)),

  /** Base role: analytics consumer — dashboards, drill-downs, report generation in scope. */
  ANALYST(Set.of(DASHBOARD_VIEW, REPORT_GENERATE, REPORT_EXPORT, AI_AGENT_INVOKE)),

  /**
   * Seeded template: engineer — team dashboards and own work-item context only. No {@link
   * Permission#REPORT_GENERATE}/{@link Permission#REPORT_EXPORT} (SecurityModel §4: MEMBER's row
   * carries neither); does carry {@link Permission#AI_AGENT_INVOKE} (SecurityModel §4 marks
   * MEMBER's cell "✓ (subset)" — the subset is a scope restriction on WHAT can be explained, not a
   * narrower permission set; v0.1 grants the same flat permission as every other AI_AGENT_INVOKE
   * role).
   */
  MEMBER(Set.of(DASHBOARD_VIEW, AI_AGENT_INVOKE)),

  /**
   * Base role: read-only stakeholder. {@link Permission#REPORT_EXPORT} is marked "scope-gated" in
   * the SecurityModel §4 matrix rather than a flatly granted default — v0.1 omits it from the
   * default set pending the scope-gate mechanism, so VIEWER can view but not export.
   */
  VIEWER(Set.of(DASHBOARD_VIEW)),

  /** Seeded template: executive — org-level rollups. Same v0.1 default set as {@link #VIEWER}. */
  EXECUTIVE_VIEWER(Set.of(DASHBOARD_VIEW)),

  /** Seeded template: security/compliance — audit log only, no business-data access. */
  SECURITY_AUDITOR(Set.of(AUDIT_READ));

  // Set.of(...) is genuinely immutable at runtime; ErrorProne's ImmutableEnumChecker only
  // recognizes a fixed allow-list of static types (e.g. Guava's ImmutableSet), which this
  // dependency-free module deliberately does not depend on for a twelve-element permission set.
  @SuppressWarnings("ImmutableEnumChecker")
  private final Set<Permission> permissions;

  Role(Set<Permission> permissions) {
    this.permissions = permissions;
  }

  /**
   * Returns this role's effective permission set.
   *
   * @return an immutable set of granted permissions
   */
  public Set<Permission> permissions() {
    return permissions;
  }
}
