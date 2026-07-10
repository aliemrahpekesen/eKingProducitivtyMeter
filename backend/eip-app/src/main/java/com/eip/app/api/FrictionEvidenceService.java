/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.analytics.friction.FrictionDefinition;
import com.eip.app.api.FrictionEvidenceView.TransitionEvidenceView;
import com.eip.app.api.FrictionEvidenceView.WorkItemEvidenceView;
import com.eip.app.tenant.TenantScopedJdbc;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Assembles a team's friction evidence from the RLS-protected correlation store ({@code
 * analytics.flow_correlation}) joined to the canonical artifacts it stitched (work items + their
 * transitions, pull requests, builds, quality gates). All reads run in one tenant-scoped
 * transaction, so a team's evidence is visible only to its own tenant. Team-level only — no
 * member/individual data is read (Law 6 / NFR-071).
 */
@Component
public class FrictionEvidenceService {

  private final TenantScopedJdbc jdbc;

  public FrictionEvidenceService(TenantScopedJdbc jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Builds the evidence for one team.
   *
   * @param teamId the team to drill into
   * @return the team's work items with their correlation evidence and transition timelines
   */
  public FrictionEvidenceView evidence(UUID teamId) {
    return jdbc.read(
        client -> {
          String teamName = readTeamName(client, teamId);
          List<WorkItemEvidenceView> items = readItems(client, teamId);
          return new FrictionEvidenceView(teamId, teamName, FrictionDefinition.VERSION, items);
        });
  }

  private static @Nullable String readTeamName(JdbcClient client, UUID teamId) {
    return client
        .sql("SELECT name FROM core.team WHERE id = :id AND deleted_at IS NULL")
        .param("id", teamId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private List<WorkItemEvidenceView> readItems(JdbcClient client, UUID teamId) {
    List<ItemRow> rows =
        client
            .sql(
                "SELECT fc.work_item_id, er.external_key AS work_item_key, wi.title, wi.type,"
                    + " wi.status, fc.cycle_time_sec, fc.active_sec, fc.blocked_sec,"
                    + " fc.review_wait_sec, fc.waiting_sec, fc.rework_count,"
                    + " pr.source_key AS pr_key, b.source_key AS build_key, b.status AS build_status,"
                    + " qg.source_key AS gate_key, qg.status AS gate_status "
                    + "FROM analytics.flow_correlation fc "
                    + "JOIN work.work_item wi ON wi.id = fc.work_item_id "
                    + "LEFT JOIN core.external_ref er ON er.entity_type = 'WORK_ITEM'"
                    + " AND er.entity_id = fc.work_item_id "
                    + "LEFT JOIN scm.pull_request pr ON pr.id = fc.pull_request_id "
                    + "LEFT JOIN cicd.build b ON b.id = fc.build_id "
                    + "LEFT JOIN quality.quality_gate qg ON qg.id = fc.quality_gate_id "
                    + "WHERE fc.team_id = :teamId "
                    + "ORDER BY er.external_key")
            .param("teamId", teamId)
            .query(
                (rs, rowNum) ->
                    new ItemRow(
                        rs.getObject("work_item_id", UUID.class),
                        rs.getString("work_item_key"),
                        rs.getString("title"),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getLong("cycle_time_sec"),
                        rs.getLong("active_sec"),
                        rs.getLong("blocked_sec"),
                        rs.getLong("review_wait_sec"),
                        rs.getLong("waiting_sec"),
                        rs.getInt("rework_count"),
                        rs.getString("pr_key"),
                        rs.getString("build_key"),
                        rs.getString("build_status"),
                        rs.getString("gate_key"),
                        rs.getString("gate_status")))
            .list();

    return rows.stream().map(row -> row.toView(readTransitions(client, row.workItemId()))).toList();
  }

  private List<TransitionEvidenceView> readTransitions(JdbcClient client, UUID workItemId) {
    return client
        .sql(
            "SELECT seq, from_state, to_state, extract(epoch from occurred_at)::bigint AS at_sec "
                + "FROM work.work_item_transition WHERE work_item_id = :id ORDER BY seq")
        .param("id", workItemId)
        .query(
            (rs, rowNum) ->
                new TransitionEvidenceView(
                    rs.getInt("seq"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getLong("at_sec")))
        .list();
  }

  private record ItemRow(
      UUID workItemId,
      String workItemKey,
      String title,
      String type,
      String status,
      long cycleTimeSec,
      long activeSec,
      long blockedSec,
      long reviewWaitSec,
      long waitingSec,
      int reworkCount,
      String prKey,
      String buildKey,
      String buildStatus,
      String gateKey,
      String gateStatus) {

    WorkItemEvidenceView toView(List<TransitionEvidenceView> transitions) {
      return new WorkItemEvidenceView(
          workItemKey,
          title,
          type,
          status,
          cycleTimeSec,
          activeSec,
          blockedSec,
          reviewWaitSec,
          waitingSec,
          reworkCount,
          prKey,
          buildKey,
          buildStatus,
          gateKey,
          gateStatus,
          transitions);
    }
  }
}
