/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import java.util.List;
import java.util.UUID;

/**
 * One team's currently unresolved work, sorted oldest-first so the top of the list is where
 * attention is most overdue. Team-level only — no assignees or other individual attribution (Law 6
 * / NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param items the team's in-flight items, sorted by {@code ageSec} descending
 */
public record TeamInFlightView(UUID teamId, String teamName, List<InFlightItemView> items) {}
