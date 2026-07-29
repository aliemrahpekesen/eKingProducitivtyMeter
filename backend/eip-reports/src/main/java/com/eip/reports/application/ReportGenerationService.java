/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.application;

import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.GetFrictionSummaryQuery;
import com.eip.analytics.api.GetInFlightWorkQuery;
import com.eip.analytics.api.GetMetricTrendsQuery;
import com.eip.analytics.api.GetRecommendationsQuery;
import com.eip.analytics.api.TeamInFlightView;
import com.eip.analytics.api.TeamRecommendationsView;
import com.eip.analytics.api.TrendsView;
import com.eip.core.error.ValidationException;
import com.eip.reports.api.GenerateReportUseCase;
import com.eip.reports.api.ReportDocument;
import com.eip.reports.api.ReportView;
import com.eip.reports.persistence.ReportRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The deterministic {@code EXEC_SUMMARY} report engine (TASK-0022, ADR-023): composes the analytics
 * module's four query ports for the current tenant — never recomputing their metrics — assembles
 * the result with the pure {@link ReportComposer}, and persists it via {@link ReportRepository}.
 * Each composed read runs in its own tenant-bound transaction, mirroring how {@code
 * MetricsController}/{@code InsightsController} invoke the same ports directly (the query ports
 * manage their own transaction boundary); the persistence write opens a separate tenant-bound
 * transaction afterward.
 */
@Service
public class ReportGenerationService implements GenerateReportUseCase {

  /** The only report type v0.1 generates. */
  public static final String EXEC_SUMMARY = "EXEC_SUMMARY";

  /** Minimum accepted window width, in weeks (mirrors {@code GetMetricTrendsQuery}). */
  public static final int MIN_WEEKS = 4;

  /** Maximum accepted window width, in weeks. */
  public static final int MAX_WEEKS = 52;

  /** The generator identity persisted on every report row. */
  static final String GENERATED_BY_AGENT = "deterministic/exec-summary-v1";

  private final GetMetricTrendsQuery trends;
  private final GetRecommendationsQuery recommendations;
  private final GetInFlightWorkQuery inFlight;
  private final GetFrictionSummaryQuery summary;
  private final ReportRepository repository;

  /**
   * Creates the service.
   *
   * @param trends the metric-trends query port
   * @param recommendations the recommendations query port
   * @param inFlight the in-flight-work query port
   * @param summary the friction-summary query port
   * @param repository the report store
   */
  public ReportGenerationService(
      GetMetricTrendsQuery trends,
      GetRecommendationsQuery recommendations,
      GetInFlightWorkQuery inFlight,
      GetFrictionSummaryQuery summary,
      ReportRepository repository) {
    this.trends = trends;
    this.recommendations = recommendations;
    this.inFlight = inFlight;
    this.summary = summary;
    this.repository = repository;
  }

  @Override
  public ReportView generate(GenerateReportCommand command) {
    if (!EXEC_SUMMARY.equals(command.type())) {
      throw new ValidationException(
          "report type must be " + EXEC_SUMMARY + ", was: " + command.type());
    }
    if (command.weeks() < MIN_WEEKS || command.weeks() > MAX_WEEKS) {
      throw new ValidationException(
          "weeks must be between " + MIN_WEEKS + " and " + MAX_WEEKS + ", was: " + command.weeks());
    }

    TrendsView trendsView = trends.trends(command.weeks());
    List<TeamRecommendationsView> recommendationsView = recommendations.recommendations();
    List<TeamInFlightView> inFlightView = inFlight.inFlight();
    FrictionSummaryView summaryView = summary.summary();

    // The one sanctioned wall-clock read, taken once: bookkeeping only (ReportDocument.generatedAt
    // + the persisted row's completed_at), never an input to totals/teams arithmetic.
    Instant generatedAt = Instant.now();
    ReportDocument document =
        ReportComposer.compose(
            command.weeks(),
            generatedAt,
            trendsView,
            recommendationsView,
            inFlightView,
            summaryView);

    return repository.insert(command.type(), GENERATED_BY_AGENT, document);
  }
}
