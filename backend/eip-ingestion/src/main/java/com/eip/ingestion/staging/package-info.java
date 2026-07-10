/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Raw-staging ingestion (TASK-0016 INC-1, DatabasePlan §2). {@link
 * com.eip.ingestion.staging.StagingRawSink} persists the {@link com.eip.connectors.spi.RawRecord}s
 * a connector emits into {@code staging.raw_simulation}, and {@link
 * com.eip.ingestion.staging.SimulationIngestionService} drives one connector sync against a
 * tenant-bound {@link java.sql.Connection}. Idempotency is content-hash based: re-ingesting the
 * same source dataset upserts each row in place — an unchanged payload is a no-op — so replays
 * never duplicate and never churn {@code ingested_at}.
 *
 * <p>This stays a plain-JDBC library (no Spring), like {@code eip-tenancy}: the caller (eip-app)
 * opens the transaction and binds the RLS tenant GUC before invoking the service, and the {@code
 * INSERT} keys {@code tenant_id} off {@code current_setting('app.tenant_id')} so a row can only
 * ever be written under — and read back through — the bound tenant's RLS policy.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.ingestion.staging;

import org.jspecify.annotations.NullMarked;
