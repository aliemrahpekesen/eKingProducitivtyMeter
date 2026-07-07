/**
 * Object-storage SPI root (BackendPlan §1: S3/MinIO object-storage abstraction). Package root only
 * — no interfaces yet. The storage SPI is defined by its owning task when artifact storage lands;
 * reserving the package here keeps the module boundary stable (TASK-0005 approach, condition C2).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.core.storage;

import org.jspecify.annotations.NullMarked;
