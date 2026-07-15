/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A cursor-paginated page of {@link ReportView}s (APIDesign §1.4 envelope), newest first.
 *
 * @param items the page of reports
 * @param nextCursor an opaque cursor to fetch the next page, or {@code null} when {@code hasMore}
 *     is false
 * @param hasMore true when more results exist beyond this page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportPageView(
    List<ReportView> items, @Nullable String nextCursor, boolean hasMore) {}
