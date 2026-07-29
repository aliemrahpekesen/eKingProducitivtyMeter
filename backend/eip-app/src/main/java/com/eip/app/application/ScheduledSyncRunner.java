/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorAdminView;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorTypeView;
import com.eip.ingestion.api.SyncMode;
import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Auto-syncs registered connectors on a fixed delay (TASK-0021 Wave 2C), so freshness does not
 * depend on an operator clicking "Sync now" or a source's webhook firing. Iterates tenants (mirrors
 * {@code OutboxRelayService}'s per-tenant loop shape — {@code ManageTenantsUseCase.list()}, one
 * pass per tenant) and, for each tenant, binds {@link TenantContextHolder} exactly as {@code
 * TenantContextFilter} does (the only tenant-bound port available here, {@link
 * ManageConnectorsUseCase}, always reads the thread-bound tenant). One connector's failure is
 * caught and counted, never stopping the loop or the remaining tenants. Each synced connector runs
 * through the full pipeline (staging, normalization, friction recompute) — one recompute per synced
 * connector, idempotent and deterministic; debouncing multiple connectors' recomputes into one pass
 * is future work.
 *
 * <p>Skips DISABLED connectors, connectors whose type has no installed sync implementation, and —
 * deliberately — {@code simulation() == true} connectors: rewriting the deterministic demo dataset
 * on a timer would be surprising and never the point of "auto-sync".
 */
@Component
@ConditionalOnProperty(prefix = "eip.sync", name = "scheduled-enabled", matchIfMissing = true)
public class ScheduledSyncRunner {

  private static final Logger log = LoggerFactory.getLogger(ScheduledSyncRunner.class);

  private final ManageTenantsUseCase tenants;
  private final ManageConnectorsUseCase connectors;
  private final SyncConnectorUseCase connectorSync;
  private final Counter ok;
  private final Counter failed;

  /**
   * Creates the runner.
   *
   * @param tenants the platform tenant directory
   * @param connectors the connector-admin port (type catalog + per-tenant listing)
   * @param connectorSync the full sync pipeline each eligible connector is synced through
   * @param registry the Micrometer registry
   */
  public ScheduledSyncRunner(
      ManageTenantsUseCase tenants,
      ManageConnectorsUseCase connectors,
      SyncConnectorUseCase connectorSync,
      MeterRegistry registry) {
    this.tenants = tenants;
    this.connectors = connectors;
    this.connectorSync = connectorSync;
    this.ok =
        Counter.builder("eip.sync.scheduled.ok")
            .description("Scheduled auto-syncs that completed without error")
            .register(registry);
    this.failed =
        Counter.builder("eip.sync.scheduled.failed")
            .description("Scheduled auto-syncs that failed (one connector never stops the loop)")
            .register(registry);
  }

  /** Runs every {@code eip.sync.auto-delay-ms} (default 300000 = 5 minutes). */
  @Scheduled(fixedDelayString = "${eip.sync.auto-delay-ms:300000}")
  public void run() {
    Map<String, Boolean> syncAvailableByType =
        connectors.types().stream()
            .collect(Collectors.toMap(ConnectorTypeView::type, ConnectorTypeView::syncAvailable));
    for (TenantView tenant : tenants.list()) {
      TenantContextHolder.set(TenantContext.of(tenant.id()));
      try {
        for (ConnectorAdminView connector : connectors.list()) {
          if (!isEligible(connector, syncAvailableByType)) {
            continue;
          }
          try {
            connectorSync.sync(connector.id(), SyncMode.AUTO);
            ok.increment();
          } catch (RuntimeException e) {
            failed.increment();
            log.warn(
                "scheduled auto-sync failed for connector {}: {}", connector.id(), e.getMessage());
          }
        }
      } finally {
        TenantContextHolder.clear();
      }
    }
  }

  private static boolean isEligible(
      ConnectorAdminView connector, Map<String, Boolean> syncAvailableByType) {
    return "ACTIVE".equals(connector.status())
        && !connector.simulation()
        && syncAvailableByType.getOrDefault(connector.type(), false);
  }
}
