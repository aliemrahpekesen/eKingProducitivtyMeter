/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Extracts a Jira issue key (e.g. {@code PLAT-101}) from connector-supplied free text, so pull
 * requests and other cross-source artifacts can be linked to their originating work item without
 * every connector re-implementing its own regex (shared across Bitbucket, GitHub, GitLab, …).
 */
public final class JiraKeyExtractor {

  private static final Pattern KEY_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]+-\\d+\\b");

  private JiraKeyExtractor() {}

  /**
   * Finds the first Jira issue key, preferring a match in the branch name over the title.
   *
   * @param branchName the source branch name (searched first)
   * @param title the artifact title (fallback when the branch has no match)
   * @return the first matching key, or {@code null} when neither text contains one
   */
  public static @Nullable String extract(String branchName, String title) {
    @Nullable String fromBranch = find(branchName);
    return fromBranch != null ? fromBranch : find(title);
  }

  private static @Nullable String find(String text) {
    Matcher matcher = KEY_PATTERN.matcher(text);
    return matcher.find() ? matcher.group() : null;
  }
}
