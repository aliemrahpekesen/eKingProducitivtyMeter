/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

/**
 * The channel a {@link Connector} emits {@link RawRecord}s into during a sync (ConnectorFramework
 * §3). The connector is oblivious to where they land — {@code eip-ingestion} provides the
 * implementation that persists to {@code staging.raw_<connector>} under the caller's tenant (RLS),
 * with content-hash idempotency. Keeping this an interface lets connectors be unit-tested against a
 * capturing sink with no database.
 */
@FunctionalInterface
public interface RawSink {

  /**
   * Emits one raw record for staging.
   *
   * @param record the raw source record
   */
  void emit(RawRecord record);
}
