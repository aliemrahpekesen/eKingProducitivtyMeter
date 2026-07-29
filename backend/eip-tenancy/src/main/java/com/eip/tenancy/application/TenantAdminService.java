/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.application;

import com.eip.core.error.ValidationException;
import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.audit.api.AuditActorType;
import com.eip.tenancy.audit.api.AuditCategory;
import com.eip.tenancy.audit.api.AuditEvent;
import com.eip.tenancy.audit.api.AuditOutcome;
import com.eip.tenancy.audit.api.RecordAuditEventUseCase;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.persistence.TenantAdminRepository;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Platform-level tenant administration over {@code core.tenant} (no RLS by design). */
@Service
public class TenantAdminService implements ManageTenantsUseCase {

  private final TenantAdminRepository repository;
  private final @Nullable TenantTransactionRunner tx;
  private final @Nullable RecordAuditEventUseCase audit;

  /**
   * Creates the service with no audit wiring. Retained ONLY so {@code AuditChainIntegrationTest}
   * (Wave 3A's own {@code com.eip.tenancy.audit} package, out of this wave's write-set) keeps
   * constructing this service unchanged — that test's row-counting assertions depend on {@code
   * create} writing NO audit row when built this way. Every other caller, including Spring's own
   * injection, uses the fully-wired constructor below.
   *
   * @param repository the persistence adapter
   */
  public TenantAdminService(TenantAdminRepository repository) {
    this(repository, null, null);
  }

  /**
   * Creates the service with Wave 3B's {@code tenant.created} audit wiring (DEBT-024, SecurityModel
   * §11).
   *
   * @param repository the persistence adapter
   * @param tx binds RLS to the newly created tenant's own id for the audit write — tenant creation
   *     itself runs with no tenant bound yet ({@code core.tenant} has no RLS), so the audit event,
   *     which IS tenant-partitioned, opens its own short transaction scoped to the tenant just
   *     created
   * @param audit the audit write path
   */
  @Autowired
  public TenantAdminService(
      TenantAdminRepository repository,
      @Nullable TenantTransactionRunner tx,
      @Nullable RecordAuditEventUseCase audit) {
    this.repository = repository;
    this.tx = tx;
    this.audit = audit;
  }

  @Override
  public TenantView create(String name, String slug) {
    String normalized = slug.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9][a-z0-9-]{1,62}")) {
      throw new ValidationException(
          "slug must be 2-63 chars of lowercase letters, digits and dashes");
    }
    if (repository.slugExists(normalized)) {
      throw new ValidationException("a tenant with slug '" + normalized + "' already exists");
    }
    TenantView created = repository.insert(name.trim(), normalized);
    recordTenantCreated(created);
    return created;
  }

  /**
   * Records the {@code tenant.created} audit event under the newly created tenant's own id — a
   * best-effort, non-blocking side effect (see {@code AuditService}'s own failure-swallowing
   * contract); skipped entirely when this instance was built without audit wiring (see the
   * single-arg constructor's javadoc). No {@code EipPrincipalHolder}-style actor is available here
   * — {@code eip-tenancy} cannot depend on {@code eip-app}'s security package (module boundary) —
   * so the actor is always recorded as {@link AuditActorType#SYSTEM}.
   */
  private void recordTenantCreated(TenantView created) {
    if (tx == null || audit == null) {
      return;
    }
    tx.run(
        TenantContext.of(created.id()),
        () ->
            audit.record(
                new AuditEvent(
                    AuditCategory.ADMIN,
                    "tenant.created",
                    AuditOutcome.SUCCESS,
                    AuditActorType.SYSTEM,
                    null,
                    Map.of("tenantId", created.id(), "slug", created.slug()))));
  }

  @Override
  public List<TenantView> list() {
    return repository.list();
  }
}
