/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.reports.api.GenerateReportUseCase;
import com.eip.reports.api.GenerateReportUseCase.GenerateReportCommand;
import com.eip.reports.api.GetReportQuery;
import com.eip.reports.api.ListReportsQuery;
import com.eip.reports.api.ReportDocumentView;
import com.eip.reports.api.ReportPageView;
import com.eip.reports.api.ReportView;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deterministic report-generation/read surface (TASK-0022, ADR-023). A pure DTO adapter
 * (BackendPlan §2.4): every endpoint delegates to the {@code eip-reports} module's ports; tenancy,
 * transactions, composition, and rendering live behind them. Team-level only (Law 6 / NFR-071) —
 * every composed analytics view already is.
 */
@RestController
@RequestMapping("/api/v1")
public class ReportsController {

  private final GenerateReportUseCase generate;
  private final ListReportsQuery reports;
  private final GetReportQuery report;

  /**
   * Creates the controller.
   *
   * @param generate the report-generation port
   * @param reports the report-list query port
   * @param report the report-detail/HTML query port
   */
  public ReportsController(
      GenerateReportUseCase generate, ListReportsQuery reports, GetReportQuery report) {
    this.generate = generate;
    this.reports = reports;
    this.report = report;
  }

  /**
   * Generates and persists a new report for the current tenant.
   *
   * @param request the report request
   * @param response the servlet response, for the {@code Location} header (a plain return type
   *     keeps {@code @ResponseStatus} — not a manually built {@code ResponseEntity} — the source
   *     springdoc infers 201 from, matching every other creating endpoint in this controller layer)
   * @return the created report's summary
   */
  @PostMapping("/reports")
  @ResponseStatus(HttpStatus.CREATED)
  public ReportView generate(
      @Valid @RequestBody GenerateReportRequest request, HttpServletResponse response) {
    ReportView view = generate.generate(new GenerateReportCommand(request.type(), request.weeks()));
    response.setHeader(HttpHeaders.LOCATION, "/api/v1/reports/" + view.id());
    return view;
  }

  /**
   * Lists the current tenant's reports, cursor-paginated, newest first.
   *
   * @param cursor opaque cursor from a previous page's {@code nextCursor}, or absent for the first
   *     page
   * @param limit page size, clamped to [1, {@value ListReportsQuery#MAX_LIMIT}] (default {@value
   *     ListReportsQuery#DEFAULT_LIMIT})
   * @return a page of the tenant's reports
   */
  @GetMapping("/reports")
  public ReportPageView listReports(
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "limit", defaultValue = "" + ListReportsQuery.DEFAULT_LIMIT) int limit) {
    return reports.list(cursor, limit);
  }

  /**
   * Returns one report's summary + full generated document.
   *
   * @param id the report id
   * @return the report and its document
   */
  @GetMapping("/reports/{id}")
  public ReportDocumentView get(@PathVariable UUID id) {
    return report.get(id);
  }

  /**
   * Returns one report rendered to self-contained HTML, for viewing or printing.
   *
   * @param id the report id
   * @return the rendered HTML page, {@code Content-Disposition: inline}
   */
  @GetMapping("/reports/{id}/html")
  public ResponseEntity<String> html(@PathVariable UUID id) {
    String html = report.html(id);
    return ResponseEntity.ok()
        .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"eip-report-" + id + ".html\"")
        .body(html);
  }

  /**
   * Report-creation payload.
   *
   * @param type the report type ({@code "EXEC_SUMMARY"} is the only value v0.1 accepts)
   * @param weeks the requested trend window width, in weeks (must be in {@code 4..52})
   */
  public record GenerateReportRequest(@NotBlank String type, int weeks) {}
}
