/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.application;

import com.eip.ai.api.NarrativeRejectedException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure numeric cross-check (ADR-024): extracts every numeric token from a narrative and verifies
 * each one, normalized, appears in a whitelist derived the same way from the prompt that was
 * offered to the LLM ({@link PromptComposer}). This is the platform's one hard guardrail against a
 * hallucinated number reaching a user — every number must be whitelisted, exactly, after
 * normalization; there is no fuzzy tolerance and no exemption for small integers.
 *
 * <p><b>Normalization</b> strips a trailing {@code %} or {@code h} unit suffix, removes thousands-
 * separator commas, and drops a trailing {@code .0}-style zero tail, so {@code "85%"} and {@code
 * "85"} are the same token, and so are {@code "17.0"} and {@code "17"}.
 *
 * <p><b>Known, accepted imprecision</b> (the honest price of a strict, no-exemption rule, ADR-024):
 * small ordinals/counts an LLM writes in prose (e.g. "the top 3 teams") will almost always
 * coincidentally match SOME real number in the prompt (team counts, list indices, etc.) and pass
 * even when not literally "the same" value the LLM meant; conversely, digits embedded in a composed
 * week-start date (e.g. {@code "2026"}, {@code "01"}, {@code "05"}) become incidentally whitelisted
 * numbers, a narrow surface an adversarial narrative could exploit. Both are accepted trade-offs of
 * a token-level, not semantic, check.
 */
final class NumericCrossChecker {

  private static final Pattern NUMBER = Pattern.compile("\\b\\d[\\d,]*(?:\\.\\d+)?(?:%|h)?");

  private NumericCrossChecker() {}

  /**
   * Extracts and normalizes every numeric token in {@code text}.
   *
   * @param text the text to scan
   * @return the normalized numeric tokens found, in ascending lexical order
   */
  static Set<String> extractNormalized(String text) {
    Set<String> tokens = new TreeSet<>();
    Matcher matcher = NUMBER.matcher(text);
    while (matcher.find()) {
      tokens.add(normalize(matcher.group()));
    }
    return tokens;
  }

  /**
   * Verifies every number {@code narrative} cites is present in {@code whitelist}.
   *
   * @param narrative the generated narrative text
   * @param whitelist the normalized numeric tokens that were offered to the LLM (from {@link
   *     PromptComposer})
   * @return the normalized numeric tokens {@code narrative} cites (a subset of {@code whitelist})
   * @throws NarrativeRejectedException if {@code narrative} cites any number not in {@code
   *     whitelist}
   */
  static Set<String> verify(String narrative, Set<String> whitelist) {
    Set<String> cited = extractNormalized(narrative);
    Set<String> invented = new TreeSet<>(cited);
    invented.removeAll(whitelist);
    if (!invented.isEmpty()) {
      throw new NarrativeRejectedException(
          "narrative cites "
              + invented.size()
              + " number(s) not present in the source data offered to it");
    }
    return cited;
  }

  private static String normalize(String raw) {
    String value = raw;
    if (value.endsWith("%") || value.endsWith("h")) {
      value = value.substring(0, value.length() - 1);
    }
    value = value.replace(",", "");
    if (value.contains(".")) {
      value = value.replaceAll("0+$", "");
      if (value.endsWith(".")) {
        value = value.substring(0, value.length() - 1);
      }
    }
    return value;
  }
}
