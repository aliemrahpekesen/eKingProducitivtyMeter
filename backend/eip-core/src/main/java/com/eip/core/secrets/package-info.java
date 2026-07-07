/**
 * Secrets / KMS SPI root (BackendPlan §1: AES-256-GCM envelope encryption, KMS SPI). Package root
 * only — no interfaces yet. The secrets SPI is defined by its owning task (P0-E4-S1); reserving the
 * package here keeps the module boundary stable without pre-committing the contract (TASK-0005
 * approach, condition C2).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.core.secrets;

import org.jspecify.annotations.NullMarked;
