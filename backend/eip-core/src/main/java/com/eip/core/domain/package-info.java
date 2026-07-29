/**
 * Shared-kernel domain value types: the {@code WorkItem} type discriminator, canonical entity
 * names, {@link com.eip.core.domain.ExternalRef} provenance, and the canonical UUIDv7 identity
 * generator (DomainModel §1–§2). Types only — no persistence, no business logic.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.core.domain;

import org.jspecify.annotations.NullMarked;
