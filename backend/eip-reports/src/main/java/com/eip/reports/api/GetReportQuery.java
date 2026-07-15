/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

import java.util.UUID;

/**
 * Reads one report's full generated content, either as a typed {@link ReportDocument} or rendered
 * to self-contained HTML.
 */
public interface GetReportQuery {

  /**
   * Reads one report's summary + full document.
   *
   * @param id the report id
   * @return the report and its document
   * @throws com.eip.core.error.ResourceNotFoundException if no report with that id is visible to
   *     the current tenant
   */
  ReportDocumentView get(UUID id);

  /**
   * Renders one report's document to self-contained, print-optimized HTML ({@code
   * com.eip.reports.application.ReportHtmlRenderer}).
   *
   * @param id the report id
   * @return the rendered HTML document
   * @throws com.eip.core.error.ResourceNotFoundException if no report with that id is visible to
   *     the current tenant
   */
  String html(UUID id);
}
