/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import java.util.UUID;

/**
 * A connector as shown by the connector-administration surface: identity, type, name, lifecycle
 * status, and whether it runs against simulation data. Read tenant-scoped under RLS.
 *
 * @param id connector id
 * @param type connector type (vendor-neutral: {@code jira}, {@code bitbucket}, {@code sonarqube},
 *     …)
 * @param name display name
 * @param status lifecycle status (REGISTERED…ACTIVE/DEGRADED/DISABLED)
 * @param simulation true when backed by simulation data (no real source connectivity yet)
 */
public record ConnectorView(UUID id, String type, String name, String status, boolean simulation) {}
