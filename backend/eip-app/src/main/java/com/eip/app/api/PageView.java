/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The canonical cursor-paginated list envelope for every {@code /api/v1} collection response
 * (APIDesign §1.4): {@code { items, nextCursor, hasMore }}. Cursors are opaque to clients — they
 * encode the sort key + tie-breaker and are never constructed or inspected client-side. Keeping the
 * envelope shape fixed from v1 means adding real paging behavior later is additive, not breaking.
 * {@code nextCursor} is omitted from the body when there is no next page ({@code hasMore = false}).
 *
 * @param items the page of results
 * @param nextCursor an opaque cursor to fetch the next page, or {@code null} when {@code hasMore}
 *     is false
 * @param hasMore true when more results exist beyond this page
 * @param <T> the element type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageView<T>(List<T> items, @Nullable String nextCursor, boolean hasMore) {}
