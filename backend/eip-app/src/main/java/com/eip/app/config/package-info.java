/**
 * App composition-root configuration and dev/demo bootstrap. The demo seeder ({@code demo} profile
 * only) inserts deterministic simulation data so the first visible slice can be demonstrated
 * without real connectors; it never runs in prod or in tests.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.config;

import org.jspecify.annotations.NullMarked;
