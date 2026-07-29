/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Minimal shared HTTP plumbing for real connector implementations: JDK {@link
 * java.net.http.HttpClient} (blocking, virtual-thread friendly — BackendPlan §4), Basic/Bearer auth
 * helpers, and JSON responses via Jackson. Framework-free by design.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.connectors.http;

import org.jspecify.annotations.NullMarked;
