/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.rbac;

/**
 * The RBAC permission catalog (SecurityModel §4 permission-catalog table). Every {@code /api/v1}
 * endpoint declares the subset of this enum it requires; {@link Role#permissions()} is the
 * authoritative role→permission matrix a caller's effective permission set is drawn from. {@link
 * #wireId()} is the stable string used in JWT/service-token scopes and OpenAPI's {@code
 * x-eip-permission} extension — never the enum name, so renaming a Java constant cannot silently
 * change an external contract.
 */
public enum Permission {

  /** Platform/tenant lifecycle management (create/configure tenants). */
  TENANT_MANAGE("tenant.manage"),

  /** User and role-assignment management within a tenant (or platform-wide for PLATFORM_ADMIN). */
  USER_MANAGE("user.manage"),

  /** Create/update connector configuration (non-secret fields, status, sync trigger). */
  CONNECTOR_CONFIGURE("connector.configure"),

  /** Write (create/rotate) a connector's secret value. Never implies read-back. */
  CONNECTOR_SECRET_WRITE("connector.secret.write"),

  /**
   * Reveal a connector secret's plaintext. Opt-in and always audited (SecurityModel §6) — never
   * part of a role's default permission set in v0.1 (see {@link Role#TENANT_ADMIN}).
   */
  CONNECTOR_SECRET_REVEAL("connector.secret.reveal"),

  /** Read team-level dashboards, metrics, friction, and recommendations. */
  DASHBOARD_VIEW("dashboard.view"),

  /** Generate a new report artifact. */
  REPORT_GENERATE("report.generate"),

  /** Export/download a generated report (e.g. the rendered HTML view). */
  REPORT_EXPORT("report.export"),

  /** Read the audit log. */
  AUDIT_READ("audit.read"),

  /** Platform operations: health, upgrades, KMS rotation. PLATFORM_ADMIN only. */
  PLATFORM_OPERATE("platform.operate"),

  /** Invoke an AI agent capability (e.g. the AI explanation layer's explain/narrate actions). */
  AI_AGENT_INVOKE("ai.agent.invoke"),

  /** Manage a tenant's AI policy (provider, model, budgets, egress). */
  AI_POLICY_MANAGE("ai.policy.manage");

  private final String wireId;

  Permission(String wireId) {
    this.wireId = wireId;
  }

  /**
   * Returns the stable wire identifier (JWT/service-token scopes, {@code x-eip-permission}).
   *
   * @return the wire id, e.g. {@code "dashboard.view"}
   */
  public String wireId() {
    return wireId;
  }
}
