/**
 * Vector-store SPI root (BackendPlan §1: VectorStore SPI, pgvector default). Package root only — no
 * interfaces yet. The VectorStore SPI is defined by its owning task in the AI phase (Sprint-3);
 * reserving the package here keeps the module boundary stable (TASK-0005 approach, condition C2).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.core.vector;

import org.jspecify.annotations.NullMarked;
