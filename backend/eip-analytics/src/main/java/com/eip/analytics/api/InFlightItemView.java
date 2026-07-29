/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

/**
 * One unresolved work item, as surfaced by the in-flight work view. Identifies the artifact only —
 * never an assignee, reporter, or other individual (Law 6 / NFR-071).
 *
 * @param workItemKey the source work-item key ({@code external_ref.external_key}), falling back to
 *     the work item's title, or {@code "—"} if neither is available
 * @param title the work-item title
 * @param state the work item's canonical status
 * @param ageSec how long the item has been open, in seconds ({@code now() - createdInSource} — the
 *     one sanctioned wall-clock read in this API, since in-flight age is inherently a live signal)
 * @param blocked whether the item is currently in the {@code BLOCKED} state
 */
public record InFlightItemView(
    String workItemKey, String title, String state, long ageSec, boolean blocked) {}
