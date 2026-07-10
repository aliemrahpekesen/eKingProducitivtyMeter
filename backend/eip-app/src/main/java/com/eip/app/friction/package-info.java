/**
 * Composition-root wiring for the friction pipeline (TASK-0016 INC-2). {@link
 * com.eip.app.friction.FrictionPipelineRunner} drives, inside one tenant-bound transaction, the
 * full slice: raw ingestion → normalization into the canonical model → cross-tool correlation → the
 * Engineering Friction v0.1 computation → the {@code metric_fact} / {@code
 * rm_team_friction_current} read model. The pure metric engine lives in {@code eip-analytics}; the
 * raw sink in {@code eip-ingestion}; this package holds the JDBC load/store orchestration (v0.1 —
 * to move behind the async pipeline, DEBT-017/DEBT-013).
 *
 * <p>All analysis is team-level; correlation evidence identifies source artifacts (work items, PRs,
 * builds, gates) but never individuals (NFR-071).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.friction;

import org.jspecify.annotations.NullMarked;
