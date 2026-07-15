/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.application;

import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TrendPointView;
import com.eip.reports.api.ReportDocument;
import com.eip.reports.api.ReportTeamSection;
import com.eip.reports.api.ReportTotals;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link ReportDocument} to a self-contained, print-optimized HTML string (TASK-0022,
 * ADR-023): inline CSS only, no {@code <script>}, no external assets — every dynamic string is
 * HTML-escaped ({@link #esc(String)}). Pure and framework-free (no Spring), so it can be
 * unit-tested directly. Numbers are formatted with {@link Locale#US} explicitly, independent of the
 * JVM default locale; durations are rendered as hours with one decimal place.
 */
public final class ReportHtmlRenderer {

  private static final DecimalFormat HOURS =
      new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US));
  private static final NumberFormat COUNT = NumberFormat.getIntegerInstance(Locale.US);

  private static final String CSS =
      "body{font-family:Arial,Helvetica,sans-serif;max-width:800px;margin:0 auto;padding:1rem;"
          + "color:#111}"
          + "h1{font-size:1.4rem}h2{font-size:1.15rem;margin-top:1.5rem}h3{font-size:1rem}"
          + "table{border-collapse:collapse;width:100%;margin:0.5rem 0}"
          + "th,td{border:1px solid #ccc;padding:0.35rem 0.5rem;text-align:left;font-size:0.9rem}"
          + "section.team{page-break-inside:avoid;margin-bottom:1.5rem;"
          + "border-top:1px solid #ddd;padding-top:0.5rem}"
          + "footer{margin-top:2rem;font-size:0.8rem;color:#555}"
          + "@media print{body{max-width:100%}}";

  private ReportHtmlRenderer() {}

  /**
   * Renders the document.
   *
   * @param document the document to render
   * @return the self-contained HTML page
   */
  public static String render(ReportDocument document) {
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
    html.append("<title>").append(esc(document.title())).append("</title>");
    html.append("<style>").append(CSS).append("</style></head><body>");
    header(html, document);
    totals(html, document.totals());
    for (ReportTeamSection team : document.teams()) {
      team(html, team);
    }
    html.append("<footer><p>Deterministic report v")
        .append(document.reportVersion())
        .append(" — computed from recorded flow data; no AI involved.</p></footer>");
    html.append("</body></html>");
    return html.toString();
  }

  private static void header(StringBuilder html, ReportDocument document) {
    String metricVersion = document.metricVersion();
    html.append("<header>");
    html.append("<h1>").append(esc(document.title())).append("</h1>");
    html.append("<p>Period: ")
        .append(esc(dateOf(document.periodStart())))
        .append(" to ")
        .append(esc(dateOf(document.periodEnd())))
        .append("</p>");
    html.append("<p>Generated at: ").append(esc(document.generatedAt().toString())).append("</p>");
    html.append("<p>Metric version: ")
        .append(esc(metricVersion == null ? "n/a" : metricVersion))
        .append("</p>");
    html.append("</header>");
  }

  private static void totals(StringBuilder html, ReportTotals totals) {
    html.append("<section class=\"totals\"><h2>Totals</h2><table>");
    row(html, "Teams reporting", COUNT.format(totals.teamsReporting()));
    row(html, "Items resolved", COUNT.format(totals.itemsResolved()));
    row(html, "Avg cycle time", hours(totals.avgCycleSec()) + " h");
    row(html, "p85 cycle time", hours(totals.p85CycleSec()) + " h");
    row(html, "Flow efficiency", pct(totals.flowEfficiencyPct()));
    row(html, "Blocked", pct(totals.blockedPct()));
    row(html, "Review wait", pct(totals.reviewWaitPct()));
    row(html, "Avg friction score", COUNT.format(totals.avgFrictionScore()));
    html.append("</table></section>");
  }

  private static void team(StringBuilder html, ReportTeamSection team) {
    html.append("<section class=\"team\">");
    html.append("<h2>").append(esc(team.teamName())).append("</h2>");
    html.append("<p>Friction score: ")
        .append(COUNT.format(team.frictionScore()))
        .append(" &middot; Dominant cause: ")
        .append(esc(team.dominantCause()))
        .append("</p>");

    List<TrendPointView> points = team.points();
    if (!points.isEmpty()) {
      html.append("<table class=\"weekly\"><thead><tr>")
          .append("<th>Week</th><th>Resolved</th><th>Avg cycle (h)</th><th>p85 (h)</th>")
          .append("<th>Flow eff %</th><th>Blocked %</th><th>Review wait %</th><th>Friction</th>")
          .append("</tr></thead><tbody>");
      for (TrendPointView point : points) {
        html.append("<tr>")
            .append("<td>")
            .append(esc(point.weekStart()))
            .append("</td><td>")
            .append(COUNT.format(point.itemsResolved()))
            .append("</td><td>")
            .append(hours(point.avgCycleSec()))
            .append("</td><td>")
            .append(hours(point.p85CycleSec()))
            .append("</td><td>")
            .append(COUNT.format(point.flowEfficiencyPct()))
            .append("</td><td>")
            .append(COUNT.format(point.blockedPct()))
            .append("</td><td>")
            .append(COUNT.format(point.reviewWaitPct()))
            .append("</td><td>")
            .append(COUNT.format(point.frictionScore()))
            .append("</td></tr>");
      }
      html.append("</tbody></table>");
    }

    List<RecommendationView> recommendations = team.recommendations();
    if (!recommendations.isEmpty()) {
      html.append("<h3>Recommendations</h3><ul>");
      for (RecommendationView rec : recommendations) {
        html.append("<li><strong>[")
            .append(esc(rec.severity()))
            .append("] ")
            .append(esc(rec.title()))
            .append("</strong> — ")
            .append(esc(rec.rationale()));
        if (!rec.actions().isEmpty()) {
          html.append("<ul>");
          for (String action : rec.actions()) {
            html.append("<li>").append(esc(action)).append("</li>");
          }
          html.append("</ul>");
        }
        html.append("</li>");
      }
      html.append("</ul>");
    }

    html.append("<p>In-flight: ")
        .append(COUNT.format(team.inFlightCount()))
        .append(" (")
        .append(COUNT.format(team.blockedInFlightCount()))
        .append(" blocked)</p>");
    html.append("</section>");
  }

  private static void row(StringBuilder html, String label, String value) {
    html.append("<tr><th>")
        .append(esc(label))
        .append("</th><td>")
        .append(value)
        .append("</td></tr>");
  }

  private static String pct(int value) {
    return COUNT.format(value) + "%";
  }

  private static String hours(long seconds) {
    return HOURS.format(seconds / 3600.0);
  }

  private static String dateOf(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  /**
   * HTML-escapes a dynamic string; every rendered value that isn't a fixed literal goes through
   * this.
   */
  private static String esc(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
