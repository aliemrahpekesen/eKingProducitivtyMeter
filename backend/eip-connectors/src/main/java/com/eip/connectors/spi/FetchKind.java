/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

/**
 * How a {@link RawRecord} was fetched (ConnectorFramework §3). Recorded on every raw row for
 * provenance; the sync engine uses it later to reason about completeness (a {@code FULL} snapshot
 * can retire rows an {@code INCREMENTAL} poll would not). Only {@code FULL} is driven in v0.1.
 */
public enum FetchKind {
  FULL,
  INCREMENTAL,
  WEBHOOK,
  RECONCILIATION
}
