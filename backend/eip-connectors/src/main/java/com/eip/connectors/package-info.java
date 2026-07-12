/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Connectors module root (BackendPlan §3). Declared OPEN: the connector SPI and the simulation
 * connector are pure-Java contracts consumed by the ingestion module; all packages are module API.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.eip.connectors;
