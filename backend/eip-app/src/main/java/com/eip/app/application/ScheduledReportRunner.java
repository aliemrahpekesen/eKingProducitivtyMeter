/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.reports.api.GenerateReportUseCase;
import com.eip.reports.api.GenerateReportUseCase.GenerateReportCommand;
import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Generates a deterministic weekly {@code EXEC_SUMMARY} report for every tenant (TASK-0022,
 * ADR-023), so a report is always fresh without an operator clicking "Generate". Mirrors {@code
 * ScheduledSyncRunner}'s per-tenant loop shape ({@code ManageTenantsUseCase.list()}, binding {@link
 * TenantContextHolder} exactly as {@code TenantContextFilter} does) so the tenant-bound {@link
 * GenerateReportUseCase} sees the same thread-bound tenant every controller call does. One tenant's
 * failure is caught and counted, never stopping the loop or the remaining tenants. Cheap and
 * deterministic — a 4-week {@code EXEC_SUMMARY} report is a handful of bounded reads, not a job
 * worth queuing (ADR-023 defers a real job-runner to the AI-composed report types).
 */
@Component
@ConditionalOnProperty(prefix = "eip.reports", name = "scheduled-enabled", matchIfMissing = true)
public class ScheduledReportRunner {

  private static final Logger log = LoggerFactory.getLogger(ScheduledReportRunner.class);

  /** The report type + window every scheduled pass generates. */
  private static final GenerateReportCommand WEEKLY_EXEC_SUMMARY =
      new GenerateReportCommand("EXEC_SUMMARY", 4);

  private final ManageTenantsUseCase tenants;
  private final GenerateReportUseCase reports;
  private final Counter ok;
  private final Counter failed;

  /**
   * Creates the runner.
   *
   * @param tenants the platform tenant directory
   * @param reports the report-generation port every tenant's weekly pass invokes
   * @param registry the Micrometer registry
   */
  public ScheduledReportRunner(
      ManageTenantsUseCase tenants, GenerateReportUseCase reports, MeterRegistry registry) {
    this.tenants = tenants;
    this.reports = reports;
    this.ok =
        Counter.builder("eip.reports.scheduled.ok")
            .description("Scheduled weekly report generations that completed without error")
            .register(registry);
    this.failed =
        Counter.builder("eip.reports.scheduled.failed")
            .description(
                "Scheduled weekly report generations that failed (one tenant never stops the"
                    + " loop)")
            .register(registry);
  }

  /** Runs weekly, Monday 06:00 server time by default ({@code eip.reports.schedule-cron}). */
  @Scheduled(cron = "${eip.reports.schedule-cron:0 0 6 * * MON}")
  public void run() {
    for (TenantView tenant : tenants.list()) {
      TenantContextHolder.set(TenantContext.of(tenant.id()));
      try {
        reports.generate(WEEKLY_EXEC_SUMMARY);
        ok.increment();
      } catch (RuntimeException e) {
        failed.increment();
        log.warn(
            "scheduled report generation failed for tenant {}: {}", tenant.id(), e.getMessage());
      } finally {
        TenantContextHolder.clear();
      }
    }
  }
}
