/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import java.util.UUID;

/**
 * One team's engineering-friction signals and its derived score. Team-level only — there is no
 * individual attribution anywhere in this view (Law 6 / NFR-071).
 *
 * @param teamId the team
 * @param teamName the team's display name
 * @param wip work-in-progress count (context; not scored — high throughput is not friction)
 * @param wipLimitBreaches how often the team is over its WIP limit
 * @param oldestInProgressAgeSec age of the oldest still-in-progress item, in seconds
 * @param reviewQueueDepth items waiting in review
 * @param frictionScore deterministic 0–100 friction index derived from the signals above
 */
public record TeamFrictionView(
    UUID teamId,
    String teamName,
    int wip,
    int wipLimitBreaches,
    long oldestInProgressAgeSec,
    int reviewQueueDepth,
    int frictionScore) {}
