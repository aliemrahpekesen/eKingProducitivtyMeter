/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

/**
 * The outcome of one ingestion run over a connector's emitted records.
 *
 * @param emitted total records the connector emitted
 * @param inserted rows newly staged
 * @param updated rows whose payload changed (content hash differed)
 * @param unchanged rows re-emitted identically (idempotent no-ops)
 */
public record IngestionResult(int emitted, int inserted, int updated, int unchanged) {}
