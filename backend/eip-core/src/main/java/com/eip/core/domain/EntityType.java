/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

/**
 * Canonical entity names used by {@link ExternalRef} and the event envelope to identify which
 * canonical entity a provenance link or a domain event refers to (DomainModel §2.2, §6).
 *
 * <p>The model is tool-agnostic: every connector normalizes into these canonical names, and no
 * downstream consumer reads raw connector payloads (DomainModel §1). The set is seeded here from
 * the bounded contexts already modeled (Organization &amp; Tenancy, Work Management, Source
 * Control, Build &amp; Release, Operations) plus the shared-kernel entities named in BackendPlan
 * §1. Adding a value as later contexts land is an <em>additive</em>, backward-compatible change.
 */
public enum EntityType {
  // Organization & Tenancy (DomainModel §4)
  ORGANIZATION,
  BUSINESS_UNIT,
  TEAM,
  MEMBER,
  MEMBER_IDENTITY,
  ROLE,
  PRODUCT,
  PROJECT,
  ROADMAP,
  INITIATIVE,

  // Work Management (DomainModel §5)
  WORK_ITEM,
  SPRINT,
  BOARD,
  BOARD_COLUMN,
  WORKFLOW_STATE,
  DEPENDENCY,
  RISK,
  WORK_ITEM_TRANSITION,

  // Source Control (DomainModel §6)
  REPOSITORY,
  BRANCH,
  COMMIT,
  PULL_REQUEST,
  CODE_REVIEW,

  // Build & Release / Operations (DomainModel §7, BackendPlan §1 shared kernel)
  RELEASE,
  DEPLOYMENT,
  INCIDENT,

  // Correlation (DomainModel §2.4)
  ENTITY_LINK
}
