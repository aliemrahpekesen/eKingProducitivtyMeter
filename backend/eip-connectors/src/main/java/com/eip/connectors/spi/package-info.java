/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The connector SPI (ConnectorFramework §3). Connectors are pure producers of <em>raw</em> source
 * records: a {@link com.eip.connectors.spi.Connector} pulls from a source system and emits {@link
 * com.eip.connectors.spi.RawRecord}s through the {@link com.eip.connectors.spi.RawSink} handed to
 * it on the {@link com.eip.connectors.spi.SyncContext}. A connector never writes canonical or
 * dashboard rows — normalization into the canonical model is {@code eip-ingestion}'s job, keeping
 * the source's shape (natural key + provenance + payload) intact until then.
 *
 * <p>v0.1 subset: this SPI covers the {@code sync → rawSink} emission path only. Lifecycle
 * (validate / testConnection / healthCheck), incremental/webhook fetch kinds, and checkpointing are
 * modelled in {@code RawRecord}/{@code SyncContext} enums but not yet driven — see DEBT-018.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.spi;

import org.jspecify.annotations.NullMarked;
