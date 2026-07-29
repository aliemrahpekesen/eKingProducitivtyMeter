/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One generated report's summary — the list/detail envelope without the full {@link ReportDocument}
 * body. {@code type} is currently always {@code "EXEC_SUMMARY"} (v0.1's only generator); {@code
 * status} is currently always {@code "READY"} — the deterministic engine completes synchronously,
 * so {@code QUEUED}/{@code GENERATING}/{@code FAILED} are not reachable yet (ADR-023).
 *
 * @param id the report id
 * @param type the report type
 * @param title the human-readable title
 * @param status the generation status
 * @param periodStart the inclusive start of the reported data window
 * @param periodEnd the exclusive end of the reported data window
 * @param weeks the requested window width, in weeks
 * @param createdAt when the report row was created
 * @param completedAt when generation completed
 */
public record ReportView(
    UUID id,
    String type,
    String title,
    String status,
    Instant periodStart,
    Instant periodEnd,
    int weeks,
    Instant createdAt,
    Instant completedAt) {}
