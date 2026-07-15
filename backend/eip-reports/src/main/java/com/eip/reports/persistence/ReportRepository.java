/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.persistence;

import com.eip.core.domain.UuidV7Generator;
import com.eip.core.error.InternalException;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.reports.api.GetReportQuery;
import com.eip.reports.api.ListReportsQuery;
import com.eip.reports.api.ReportDocument;
import com.eip.reports.api.ReportDocumentView;
import com.eip.reports.api.ReportPageView;
import com.eip.reports.api.ReportView;
import com.eip.reports.application.ReportHtmlRenderer;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Report store over {@code reports.generated_report} (TASK-0022, ADR-023): insert (write,
 * tenant-bound transaction), keyset-paginated list, and by-id read (both read-only tenant-bound
 * transactions). The {@code document} jsonb column is the v0.1 addition over the planned schema —
 * the full generated content lives inline; {@code parameters}/{@code data_time_range}/{@code
 * input_sources} are populated for provenance/queryability even though {@link ReportDocumentView}
 * is always rebuilt from {@code document}, never those columns.
 */
@Repository
public class ReportRepository implements ListReportsQuery, GetReportQuery {

  private final TenantTransactionRunner tx;
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final UuidV7Generator ids;

  /**
   * Creates the repository.
   *
   * @param tx the tenant-bound transaction runner
   * @param jdbc the JDBC client
   * @param mapper the shared Jackson mapper (Instant serialized as ISO-8601 via the {@code
   *     JavaTimeModule} {@code spring-boot-starter-json} auto-configures)
   * @param ids the platform's UUIDv7 id generator (the table has no {@code DEFAULT} on {@code id})
   */
  public ReportRepository(
      TenantTransactionRunner tx, JdbcClient jdbc, ObjectMapper mapper, UuidV7Generator ids) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.ids = ids;
  }

  /**
   * Persists a newly generated report.
   *
   * @param type the report type
   * @param generatedByAgent the generator identity (e.g. {@code "deterministic/exec-summary-v1"})
   * @param document the assembled document
   * @return the persisted report's summary
   */
  public ReportView insert(String type, String generatedByAgent, ReportDocument document) {
    UUID id = ids.generate();
    String documentJson = writeJson(document);
    String parametersJson = "{\"weeks\":" + document.weeks() + "}";
    Timestamp periodStart = Timestamp.from(document.periodStart());
    Timestamp periodEnd = Timestamp.from(document.periodEnd());
    Timestamp completedAt = Timestamp.from(document.generatedAt());

    Instant createdAt =
        tx.callCurrent(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO reports.generated_report
                          (id, tenant_id, type, title, parameters, status, generated_by_agent,
                           data_time_range, input_sources, document, completed_at)
                        VALUES
                          (:id, current_setting('app.tenant_id')::uuid, :type, :title,
                           :parameters::jsonb, 'READY', :generatedByAgent,
                           tstzrange(:periodStart::timestamptz, :periodEnd::timestamptz, '[)'),
                           :inputSources::jsonb, :document::jsonb, :completedAt::timestamptz)
                        RETURNING created_at
                        """)
                    .param("id", id)
                    .param("type", type)
                    .param("title", document.title())
                    .param("parameters", parametersJson)
                    .param("generatedByAgent", generatedByAgent)
                    .param("periodStart", periodStart)
                    .param("periodEnd", periodEnd)
                    .param("inputSources", "[\"analytics\"]")
                    .param("document", documentJson)
                    .param("completedAt", completedAt)
                    .query(Timestamp.class)
                    .single()
                    .toInstant());

    return new ReportView(
        id,
        type,
        document.title(),
        "READY",
        document.periodStart(),
        document.periodEnd(),
        document.weeks(),
        createdAt,
        document.generatedAt());
  }

  @Override
  public ReportPageView list(@Nullable String cursor, int limit) {
    int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
    @Nullable ReportCursor after = ReportCursor.decode(cursor);

    List<ReportView> rows = tx.readCurrent(() -> query(after, pageSize + 1));

    boolean hasMore = rows.size() > pageSize;
    List<ReportView> page = hasMore ? rows.subList(0, pageSize) : rows;
    @Nullable String nextCursor = hasMore ? ReportCursor.encode(page.get(page.size() - 1)) : null;
    return new ReportPageView(List.copyOf(page), nextCursor, hasMore);
  }

  @Override
  public ReportDocumentView get(UUID id) {
    // Thrown INSIDE the transactional lambda (never returned as null): TenantTransactionRunner
    // requires its work to produce a non-null result, and TransactionTemplate.execute() propagates
    // a RuntimeException thrown here straight out of readCurrent after a harmless rollback of the
    // read-only transaction.
    return tx.readCurrent(
        () -> {
          Row row =
              jdbc.sql(
                      """
                      SELECT id, type, title, status, created_at, completed_at, document
                      FROM reports.generated_report WHERE id = :id
                      """)
                  .param("id", id)
                  .query(ReportRepository::mapRow)
                  .optional()
                  .orElseThrow(() -> new ResourceNotFoundException("report not found: " + id));
          @Nullable String documentJson = row.documentJson();
          if (documentJson == null) {
            throw new InternalException("report " + id + " has no stored document");
          }
          ReportDocument document = readJson(documentJson);
          ReportView view =
              new ReportView(
                  row.id(),
                  row.type(),
                  row.title(),
                  row.status(),
                  document.periodStart(),
                  document.periodEnd(),
                  document.weeks(),
                  row.createdAt(),
                  row.completedAt());
          return new ReportDocumentView(view, document);
        });
  }

  @Override
  public String html(UUID id) {
    return ReportHtmlRenderer.render(get(id).document());
  }

  private List<ReportView> query(@Nullable ReportCursor after, int fetch) {
    String base =
        "SELECT id, type, title, status, created_at, completed_at,"
            + " lower(data_time_range) AS period_start, upper(data_time_range) AS period_end,"
            + " (parameters->>'weeks')::int AS weeks FROM reports.generated_report";
    JdbcClient.StatementSpec spec =
        (after == null)
            ? jdbc.sql(base + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            : jdbc.sql(
                    base
                        + " WHERE (created_at, id) < (:createdAt, :id)"
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("createdAt", Timestamp.from(after.createdAt()))
                .param("id", after.id());
    return spec.param("limit", fetch)
        .query(
            (rs, rowNum) ->
                new ReportView(
                    rs.getObject("id", UUID.class),
                    rs.getString("type"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getTimestamp("period_start").toInstant(),
                    rs.getTimestamp("period_end").toInstant(),
                    rs.getInt("weeks"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("completed_at").toInstant()))
        .list();
  }

  /** One raw {@code get(id)} row, before the {@code document} jsonb is parsed. */
  private record Row(
      UUID id,
      String type,
      String title,
      String status,
      Instant createdAt,
      Instant completedAt,
      @Nullable String documentJson) {}

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("type"),
        rs.getString("title"),
        rs.getString("status"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("completed_at").toInstant(),
        rs.getString("document"));
  }

  private String writeJson(ReportDocument document) {
    try {
      return mapper.writeValueAsString(document);
    } catch (JsonProcessingException e) {
      throw new InternalException("failed to serialize report document", e);
    }
  }

  private ReportDocument readJson(String json) {
    try {
      return mapper.readValue(json, ReportDocument.class);
    } catch (JsonProcessingException e) {
      throw new InternalException("stored report document is not valid JSON", e);
    }
  }
}
