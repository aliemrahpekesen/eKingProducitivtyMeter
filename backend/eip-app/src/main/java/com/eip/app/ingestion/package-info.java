/**
 * Composition-root wiring for the ingestion pipeline (BackendPlan §1: eip-app is the only Spring
 * app; the library modules stay framework-free). {@link
 * com.eip.app.ingestion.SimulationIngestionRunner} opens a write transaction, binds the tenant's
 * RLS GUC, and drives {@code eip-ingestion}'s {@code SimulationIngestionService} with the {@code
 * eip-connectors} simulation connector — the real ingestion path, not a dashboard-seeding shortcut.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.ingestion;

import org.jspecify.annotations.NullMarked;
