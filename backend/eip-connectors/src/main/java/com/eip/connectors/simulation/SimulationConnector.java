/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.simulation;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;

/**
 * A production-shaped connector whose "source system" is the fixed {@link SimulationDataset}. It
 * flows through exactly the same SPI as a real connector — a {@code sync} emitting {@link
 * RawRecord}s into the {@link SyncContext#rawSink()} — so the ingestion, normalization,
 * correlation, and analytics paths it feeds are the real ones, not a seeding shortcut. Marked
 * {@link #simulation()} so the platform always labels the data as simulated.
 */
public final class SimulationConnector implements Connector {

  /** The {@code core.connector.type} discriminator for this connector. */
  public static final String TYPE = "simulation";

  private final SimulationDataset dataset;

  /** Creates a connector over the fixed deterministic dataset. */
  public SimulationConnector() {
    this(new SimulationDataset());
  }

  SimulationConnector(SimulationDataset dataset) {
    this.dataset = dataset;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public boolean simulation() {
    return true;
  }

  @Override
  public void sync(SyncContext context) {
    for (RawRecord record : dataset.records()) {
      context.rawSink().emit(record);
    }
  }
}
