/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

import com.eip.tenancy.context.TenantContext;

/**
 * Ingests the deterministic simulation connector's full emission into raw staging ({@code
 * staging.raw_simulation}) for one tenant. Content-hash idempotent: replaying the same dataset
 * stages nothing new and never churns {@code ingested_at}. The connector fetch happens outside the
 * database transaction; only the batched staging writes run inside it.
 */
public interface IngestSimulationDataUseCase {

  /**
   * Runs one ingestion for the given tenant.
   *
   * @param tenant the tenant to ingest for
   * @return the ingestion outcome (emitted / inserted / updated / unchanged)
   */
  IngestionResult ingest(TenantContext tenant);
}
