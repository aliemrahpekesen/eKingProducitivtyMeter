/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The current session's tenant identity (the first "who am I" surface). Organisation name is read
 * tenant-scoped under RLS; null when the tenant has no organisation seeded yet.
 *
 * @param tenantId the resolved tenant
 * @param organizationName the tenant's organisation display name, if any
 */
public record SessionView(UUID tenantId, @Nullable String organizationName) {}
