/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TrendPointView;
import com.eip.reports.api.ReportDocument;
import com.eip.reports.api.ReportTeamSection;
import com.eip.reports.api.ReportTotals;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ReportHtmlRenderer} escapes every dynamic string (including an XSS attempt in a
 * team name), formats durations/numbers with {@link java.util.Locale#US} explicitly, and renders
 * every documented section.
 */
class ReportHtmlRendererTest {

  private static final UUID TEAM_ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

  @Test
  void escapes_a_team_name_containing_a_script_tag() {
    ReportTeamSection malicious =
        new ReportTeamSection(
            TEAM_ID, "<script>alert(1)</script>", 50, "NONE", List.of(), List.of(), 0, 0);
    ReportDocument document = documentWith(malicious);

    String html = ReportHtmlRenderer.render(document);

    assertThat(html).doesNotContain("<script>");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }

  @Test
  void renders_durations_as_hours_with_one_decimal_in_locale_us() {
    ReportTeamSection team =
        new ReportTeamSection(
            TEAM_ID,
            "Alpha",
            50,
            "NONE",
            List.of(new TrendPointView("2026-01-05", 3, 5400, 7200, 40, 10, 20, 33)),
            List.of(),
            0,
            0);
    ReportDocument document = documentWith(team);

    String html = ReportHtmlRenderer.render(document);

    // 5400s = 1.5h, 7200s = 2.0h.
    assertThat(html).contains("<td>1.5</td>");
    assertThat(html).contains("<td>2.0</td>");
  }

  @Test
  void renders_every_documented_section() {
    ReportTeamSection team =
        new ReportTeamSection(
            TEAM_ID,
            "Alpha",
            77,
            "BLOCKED",
            List.of(new TrendPointView("2026-01-05", 3, 100, 200, 40, 10, 20, 33)),
            List.of(
                new RecommendationView(
                    "R-TEST",
                    "CRITICAL",
                    "Rec title",
                    "Rec rationale",
                    List.of("Do the thing"),
                    List.of("metric=1"))),
            4,
            2);
    ReportDocument document = documentWith(team);

    String html = ReportHtmlRenderer.render(document);

    assertThat(html).contains("<title>").contains("Engineering Flow Report");
    assertThat(html).contains("Totals");
    assertThat(html).contains("Alpha");
    assertThat(html).contains("BLOCKED");
    assertThat(html).contains("Rec title");
    assertThat(html).contains("Rec rationale");
    assertThat(html).contains("Do the thing");
    assertThat(html).contains("In-flight: 4 (2 blocked)");
    assertThat(html)
        .contains("Deterministic report v1 — computed from recorded flow data; no AI involved.");
    assertThat(html).doesNotContain("<script");
  }

  private static ReportDocument documentWith(ReportTeamSection team) {
    ReportTotals totals = new ReportTotals(1, 3, 100, 200, 40, 10, 20, team.frictionScore());
    return new ReportDocument(
        ReportDocument.CURRENT_VERSION,
        "Engineering Flow Report — 2026-01-05..2026-01-19",
        Instant.parse("2026-01-05T00:00:00Z"),
        Instant.parse("2026-01-19T00:00:00Z"),
        8,
        Instant.parse("2026-02-01T12:00:00Z"),
        "engineering_friction_v0.1",
        totals,
        List.of(team));
  }
}
