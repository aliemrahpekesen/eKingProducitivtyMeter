/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.simulation;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.ConnectorDescriptor;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;
import com.eip.connectors.spi.TestConnectionOutcome;

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

  private static final ConnectorDescriptor DESCRIPTOR =
      new ConnectorDescriptor(
          TYPE,
          "Simulation Source",
          "Deterministic built-in dataset (3 teams, work items, PRs, builds, quality gates)"
              + " — demos and pipeline verification without external systems.",
          """
          {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
           "title":"Simulation","properties":{},"required":[]}
          """,
          null);

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
  public ConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public boolean simulation() {
    return true;
  }

  @Override
  public TestConnectionOutcome testConnection(ConnectorConfig config) {
    return TestConnectionOutcome.ok(
        "Simulation source reachable — " + dataset.records().size() + " records available.");
  }

  @Override
  public boolean syncAvailable() {
    return true;
  }

  @Override
  public void sync(SyncContext context) {
    for (RawRecord record : dataset.records()) {
      context.rawSink().emit(record);
    }
  }
}
