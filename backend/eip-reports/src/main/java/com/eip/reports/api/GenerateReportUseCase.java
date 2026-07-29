/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

/**
 * Generates a new deterministic report for the current tenant (TASK-0022, ADR-023: v0.1 ships one
 * deterministic engine, {@code EXEC_SUMMARY}; AI-composed report types are future work). Fails
 * closed when no tenant is bound to the calling thread (the composed analytics query ports each
 * require one).
 */
public interface GenerateReportUseCase {

  /**
   * Generates and persists a report.
   *
   * @param command the report request
   * @return the persisted report's summary
   * @throws com.eip.core.error.ValidationException if {@code command.type()} is not {@code
   *     "EXEC_SUMMARY"} or {@code command.weeks()} is outside {@code 4..52}
   */
  ReportView generate(GenerateReportCommand command);

  /**
   * A report request.
   *
   * @param type the report type ({@code "EXEC_SUMMARY"} is the only value v0.1 accepts)
   * @param weeks the requested trend window width, in weeks (must be in {@code 4..52})
   */
  record GenerateReportCommand(String type, int weeks) {}
}
