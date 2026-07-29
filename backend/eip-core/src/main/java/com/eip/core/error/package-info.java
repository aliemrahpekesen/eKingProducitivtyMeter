/**
 * Shared-kernel error taxonomy: the sealed {@link com.eip.core.error.EipException} root and its
 * kernel leaves (CodingStandards §2.4; BackendPlan §10). Every exception that crosses a module API
 * boundary extends this root via one of its leaves, giving the RFC 7807 problem+json surface a
 * single closed hierarchy.
 *
 * <p>Only the tenancy-agnostic <em>kernel</em> leaves live here. Module-specific leaves named in
 * the standards (e.g. {@code ConnectorException}, {@code LlmProviderException}) cannot be permitted
 * subtypes of a sealed type across Gradle module boundaries; how they attach to this taxonomy is
 * decided by an ADR when the first such leaf is introduced (TASK-0005 approach, risk R1 / condition
 * C1).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.core.error;

import org.jspecify.annotations.NullMarked;
