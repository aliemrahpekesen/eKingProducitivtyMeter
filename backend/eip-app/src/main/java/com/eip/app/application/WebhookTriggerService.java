/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.tenant.TenantContextFilter;
import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorAdminView;
import com.eip.ingestion.api.SyncMode;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Validates and dispatches inbound webhook triggers (TASK-0021 Wave 2C). The tenant named in the
 * webhook path is bound with the exact mechanism {@link TenantContextFilter} uses for header-
 * resolved tenants ({@link TenantContextHolder#set} / {@code clear} in a {@code finally}, plus the
 * same MDC key) — webhooks arrive from external systems that cannot set {@code X-EIP-Tenant}.
 *
 * <p>The webhook token is compared with {@link MessageDigest#isEqual} (constant-time) against the
 * connector's {@code webhookToken} config setting; it is never logged or echoed. A per-connector
 * in-memory debounce (last-accepted timestamp, {@value #DEBOUNCE_SECONDS} s window) is v0.1 storm
 * protection only — real rate limiting is Wave 3E — and is lost on restart by design (no
 * cross-instance coordination needed for this release).
 */
@Service
public class WebhookTriggerService implements TriggerWebhookSyncUseCase {

  private static final Logger log = LoggerFactory.getLogger(WebhookTriggerService.class);
  private static final int DEBOUNCE_SECONDS = 30;
  private static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(DEBOUNCE_SECONDS);
  private static final String CONFIG_TOKEN_KEY = "webhookToken";

  private final ManageConnectorsUseCase connectors;
  private final SyncConnectorUseCase connectorSync;
  private final ExecutorService webhookSyncExecutor;
  private final ConcurrentMap<UUID, Instant> lastAccepted = new ConcurrentHashMap<>();
  private final Counter accepted;
  private final Counter rejected;
  private final Counter debounced;

  /**
   * Creates the service.
   *
   * @param connectors the connector-admin port (lookup only; never mutated here)
   * @param connectorSync the full sync pipeline the dispatched incremental sync runs through —
   *     staging, normalization, friction recompute, so a webhook actually refreshes the dashboard
   * @param webhookSyncExecutor the dedicated virtual-thread executor dispatched syncs run on
   * @param registry the Micrometer registry
   */
  public WebhookTriggerService(
      ManageConnectorsUseCase connectors,
      SyncConnectorUseCase connectorSync,
      ExecutorService webhookSyncExecutor,
      MeterRegistry registry) {
    this.connectors = connectors;
    this.connectorSync = connectorSync;
    this.webhookSyncExecutor = webhookSyncExecutor;
    this.accepted =
        Counter.builder("eip.webhooks.accepted")
            .description("Webhook triggers accepted and dispatched for an incremental sync")
            .register(registry);
    this.rejected =
        Counter.builder("eip.webhooks.rejected")
            .description("Webhook triggers rejected: unknown connector or invalid token")
            .register(registry);
    this.debounced =
        Counter.builder("eip.webhooks.debounced")
            .description("Webhook triggers skipped by the in-memory storm-protection debounce")
            .register(registry);
  }

  @Override
  public WebhookOutcome trigger(UUID tenantId, UUID connectorId, @Nullable String suppliedToken) {
    TenantContext tenant = TenantContext.of(tenantId);
    bindTenant(tenant);
    try {
      Optional<ConnectorAdminView> found =
          connectors.list().stream().filter(c -> c.id().equals(connectorId)).findFirst();
      if (found.isEmpty()) {
        rejected.increment();
        return WebhookOutcome.NOT_FOUND;
      }

      @Nullable String configuredToken = found.get().config().get(CONFIG_TOKEN_KEY);
      if (configuredToken == null
          || configuredToken.isBlank()
          || !constantTimeEquals(configuredToken, suppliedToken)) {
        rejected.increment();
        log.warn("webhook rejected: missing or invalid token for connector {}", connectorId);
        return WebhookOutcome.UNAUTHORIZED;
      }

      if (isDebounced(connectorId)) {
        debounced.increment();
        return WebhookOutcome.DEBOUNCED;
      }

      accepted.increment();
      webhookSyncExecutor.execute(() -> runIncrementalSync(tenant, connectorId));
      return WebhookOutcome.ACCEPTED;
    } finally {
      unbindTenant();
    }
  }

  /**
   * Skips (returning {@code true}) a trigger arriving within {@value #DEBOUNCE_SECONDS}s of the
   * last ACCEPTED trigger for the same connector; otherwise records this instant as the new
   * last-accepted mark. Atomic per connector via {@link ConcurrentMap#compute}.
   */
  private boolean isDebounced(UUID connectorId) {
    Instant now = Instant.now();
    boolean[] debouncedFlag = {false};
    lastAccepted.compute(
        connectorId,
        (id, previous) -> {
          if (previous != null && Duration.between(previous, now).compareTo(DEBOUNCE_WINDOW) < 0) {
            debouncedFlag[0] = true;
            return previous;
          }
          return now;
        });
    return debouncedFlag[0];
  }

  /** Runs on the dedicated virtual-thread executor; binds/unbinds the tenant on that thread. */
  private void runIncrementalSync(TenantContext tenant, UUID connectorId) {
    bindTenant(tenant);
    try {
      connectorSync.sync(connectorId, SyncMode.INCREMENTAL);
    } catch (RuntimeException e) {
      log.warn(
          "webhook-triggered incremental sync failed for connector {}: {}",
          connectorId,
          e.getMessage());
    } finally {
      unbindTenant();
    }
  }

  private static void bindTenant(TenantContext tenant) {
    TenantContextHolder.set(tenant);
    MDC.put(TenantContextFilter.MDC_TENANT_ID, tenant.tenantId().toString());
  }

  private static void unbindTenant() {
    TenantContextHolder.clear();
    MDC.remove(TenantContextFilter.MDC_TENANT_ID);
  }

  /**
   * Never logs either value; returns {@code false} (not a mismatch throw) when either is absent.
   */
  private static boolean constantTimeEquals(String expected, @Nullable String actual) {
    if (actual == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
