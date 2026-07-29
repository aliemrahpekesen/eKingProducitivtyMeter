/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

/**
 * Canonical type discriminator for the {@code WorkItem} supertype (DomainModel §1 principle 3, §5).
 *
 * <p>All plannable work normalizes to a single {@code WorkItem} supertype distinguished by this
 * type — Epic, Feature, Story, Task, Bug, and Incident ticket are <em>types</em>, not separate
 * entities. The persistent {@code WorkItem} entity itself is introduced later (P1-E2-S2); this
 * kernel enum is the shared contract every module keys off.
 */
public enum WorkItemType {
  EPIC,
  FEATURE,
  STORY,
  TASK,
  BUG,
  INCIDENT_TICKET
}
