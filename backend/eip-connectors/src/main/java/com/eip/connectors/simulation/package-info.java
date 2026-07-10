/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The deterministic simulation connector (TASK-0016). {@link
 * com.eip.connectors.simulation.SimulationConnector} emits a fixed, production-shaped dataset —
 * Jira-like work items and state transitions, SCM pull requests and reviews, CI builds, and
 * SonarQube-like quality gates, with explicit cross-tool identifiers — through the real {@link
 * com.eip.connectors.spi.RawSink}. It replaces manual dashboard seeding: the numbers the platform
 * shows are computed from this data as it travels the normal ingestion → normalization →
 * correlation → analytics path.
 *
 * <p>Determinism is a hard requirement: the dataset is anchored to a fixed base instant with no
 * clock or randomness ({@link com.eip.connectors.simulation.SimulationDataset#BASE}), so a replay
 * produces byte-identical records and the computed metrics are reproducible.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.simulation;

import org.jspecify.annotations.NullMarked;
