/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

/**
 * One report's summary paired with its full generated content.
 *
 * @param report the report summary
 * @param document the full generated content
 */
public record ReportDocumentView(ReportView report, ReportDocument document) {}
