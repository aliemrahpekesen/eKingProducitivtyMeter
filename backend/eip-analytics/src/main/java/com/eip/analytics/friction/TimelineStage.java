/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.friction;

/**
 * One entry in a work item's state timeline: the state and the epoch-second it was entered.
 *
 * @param state the workflow state (e.g. {@code TODO}, {@code IN_PROGRESS}, {@code BLOCKED}, {@code
 *     IN_REVIEW}, {@code DONE})
 * @param startEpochSec when the state was entered, in epoch seconds
 */
public record TimelineStage(String state, long startEpochSec) {}
