/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.rbac;

import static com.eip.tenancy.rbac.Permission.AI_AGENT_INVOKE;
import static com.eip.tenancy.rbac.Permission.AI_POLICY_MANAGE;
import static com.eip.tenancy.rbac.Permission.AUDIT_READ;
import static com.eip.tenancy.rbac.Permission.CONNECTOR_CONFIGURE;
import static com.eip.tenancy.rbac.Permission.CONNECTOR_SECRET_REVEAL;
import static com.eip.tenancy.rbac.Permission.CONNECTOR_SECRET_WRITE;
import static com.eip.tenancy.rbac.Permission.DASHBOARD_VIEW;
import static com.eip.tenancy.rbac.Permission.PLATFORM_OPERATE;
import static com.eip.tenancy.rbac.Permission.REPORT_EXPORT;
import static com.eip.tenancy.rbac.Permission.REPORT_GENERATE;
import static com.eip.tenancy.rbac.Permission.TENANT_MANAGE;
import static com.eip.tenancy.rbac.Permission.USER_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Transcribes the SecurityModel §4 permission-catalog table row-by-row so any drift between this
 * enum and the documented contract fails the build, never just a review comment. Every assertion
 * here MUST mirror a table cell in {@code docs/architecture/SecurityModel.md} §4 exactly — do not
 * "fix" a failing assertion without first checking whether the doc or the code is wrong.
 */
class RbacMatrixTest {

  @Test
  void wireIdsMatchTheDocumentedPermissionCatalog() {
    assertThat(TENANT_MANAGE.wireId()).isEqualTo("tenant.manage");
    assertThat(USER_MANAGE.wireId()).isEqualTo("user.manage");
    assertThat(CONNECTOR_CONFIGURE.wireId()).isEqualTo("connector.configure");
    assertThat(CONNECTOR_SECRET_WRITE.wireId()).isEqualTo("connector.secret.write");
    assertThat(CONNECTOR_SECRET_REVEAL.wireId()).isEqualTo("connector.secret.reveal");
    assertThat(DASHBOARD_VIEW.wireId()).isEqualTo("dashboard.view");
    assertThat(REPORT_GENERATE.wireId()).isEqualTo("report.generate");
    assertThat(REPORT_EXPORT.wireId()).isEqualTo("report.export");
    assertThat(AUDIT_READ.wireId()).isEqualTo("audit.read");
    assertThat(PLATFORM_OPERATE.wireId()).isEqualTo("platform.operate");
    assertThat(AI_AGENT_INVOKE.wireId()).isEqualTo("ai.agent.invoke");
    assertThat(AI_POLICY_MANAGE.wireId()).isEqualTo("ai.policy.manage");
  }

  @Test
  void catalogDeclaresExactlyTheTwelveDocumentedPermissions() {
    assertThat(Permission.values()).hasSize(12);
  }

  @Test
  void catalogDeclaresExactlyTheTenDocumentedRoles() {
    assertThat(Role.values()).hasSize(10);
  }

  @Test
  void platformAdmin_row() {
    // | tenant.manage | user.manage | ... | audit.read | ... | platform.operate |  all ✓; no
    // ai.agent.invoke/ai.policy.manage (SecurityModel §4: both columns are "—" for PLATFORM_ADMIN).
    assertThat(Role.PLATFORM_ADMIN.permissions())
        .containsExactlyInAnyOrder(TENANT_MANAGE, USER_MANAGE, AUDIT_READ, PLATFORM_OPERATE)
        .doesNotContain(AI_AGENT_INVOKE, AI_POLICY_MANAGE);
  }

  @Test
  void tenantAdmin_row() {
    // TENANT_ADMIN column: tenant.manage, user.manage, connector.configure,
    // connector.secret.write, dashboard.view, report.generate, report.export, audit.read,
    // ai.agent.invoke, ai.policy.manage — all ✓. connector.secret.reveal is "opt-in, audited", NOT
    // a default-granted permission.
    assertThat(Role.TENANT_ADMIN.permissions())
        .containsExactlyInAnyOrder(
            TENANT_MANAGE,
            USER_MANAGE,
            CONNECTOR_CONFIGURE,
            CONNECTOR_SECRET_WRITE,
            DASHBOARD_VIEW,
            REPORT_GENERATE,
            REPORT_EXPORT,
            AUDIT_READ,
            AI_AGENT_INVOKE,
            AI_POLICY_MANAGE)
        .doesNotContain(CONNECTOR_SECRET_REVEAL, PLATFORM_OPERATE);
  }

  @Test
  void managerScopeTemplates_row() {
    // "Manager-scope templates" column (ENGINEERING_MANAGER/TEAM_LEAD/RELEASE_MANAGER) and ANALYST
    // share one column in the doc table: dashboard.view, report.generate, report.export,
    // ai.agent.invoke — all ✓; ai.policy.manage stays TENANT_ADMIN-only ("—" for this column).
    Set<Permission> expected =
        Set.of(DASHBOARD_VIEW, REPORT_GENERATE, REPORT_EXPORT, AI_AGENT_INVOKE);
    assertThat(Role.ENGINEERING_MANAGER.permissions())
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(Role.TEAM_LEAD.permissions()).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(Role.RELEASE_MANAGER.permissions()).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(Role.ANALYST.permissions()).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void member_row() {
    // MEMBER column: dashboard.view and ai.agent.invoke ("✓ (subset)" in SecurityModel §4 — a scope
    // restriction on what can be explained, not a narrower permission set in v0.1).
    assertThat(Role.MEMBER.permissions())
        .containsExactlyInAnyOrder(DASHBOARD_VIEW, AI_AGENT_INVOKE);
  }

  @Test
  void viewerAndExecutiveViewer_row() {
    // VIEWER / EXECUTIVE_VIEWER column: dashboard.view ✓; report.export is "scope-gated", not a
    // flatly granted default in v0.1.
    assertThat(Role.VIEWER.permissions()).containsExactly(DASHBOARD_VIEW);
    assertThat(Role.EXECUTIVE_VIEWER.permissions()).containsExactly(DASHBOARD_VIEW);
  }

  @Test
  void securityAuditor_row() {
    // SECURITY_AUDITOR column: only audit.read ✓.
    assertThat(Role.SECURITY_AUDITOR.permissions()).containsExactly(AUDIT_READ);
  }
}
