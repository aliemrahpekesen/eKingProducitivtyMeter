/**
 * The Engineering Friction v0.1 metric engine (TASK-0016 INC-2, FEAT-031). Reads the correlated
 * canonical flow (work items + their state-transition timelines, stitched to pull requests, builds,
 * and quality gates), decomposes each work item's cycle time into active / blocked / review-wait
 * with {@link com.eip.analytics.friction.FlowTimeline}, aggregates to a team-level composite with
 * {@link com.eip.analytics.friction.FrictionCalculator}, and persists the result — correlation
 * evidence, a versioned metric definition, {@code metric_fact} history, and the {@code
 * rm_team_friction_current} read model — via {@link
 * com.eip.analytics.friction.FrictionComputeService}.
 *
 * <p>The metric is <b>EXPERIMENTAL v0.1</b>: transparent, deterministic (no clock, no randomness —
 * a fixed input yields a fixed score), team-level only (never individual — NFR-071), and reproduces
 * exactly on recompute. The composite formula, weights, inputs, and gaming risks are documented on
 * {@link com.eip.analytics.friction.FrictionDefinition} and in {@code docs/metrics}.
 *
 * <p>Computation is pure Java; persistence is plain {@code java.sql} on a tenant-bound connection
 * supplied by the composition root (no Spring in this library module).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.analytics.friction;

import org.jspecify.annotations.NullMarked;
