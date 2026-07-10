/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Proves the simulation dataset is deterministic, production-shaped, cross-tool correlatable, and
 * anti-surveillance clean — the properties the whole vertical slice relies on. Also exercises the
 * {@link SimulationConnector} against a capturing sink, so the connector's emission path is covered
 * without a database.
 */
class SimulationDatasetTest {

  private final List<RawRecord> records = new SimulationDataset().records();

  private List<RawRecord> stream(String name) {
    return records.stream().filter(r -> r.stream().equals(name)).collect(Collectors.toList());
  }

  @Test
  void emits_the_expected_deterministic_record_counts_per_stream() {
    assertThat(records).hasSize(79);
    assertThat(stream(SimulationDataset.WORK_ITEM)).hasSize(9);
    assertThat(stream(SimulationDataset.TRANSITION)).hasSize(33);
    assertThat(stream(SimulationDataset.PULL_REQUEST)).hasSize(9);
    assertThat(stream(SimulationDataset.CODE_REVIEW)).hasSize(10);
    assertThat(stream(SimulationDataset.BUILD)).hasSize(9);
    assertThat(stream(SimulationDataset.QUALITY_GATE)).hasSize(9);
  }

  @Test
  void covers_three_teams() {
    assertThat(
            stream(SimulationDataset.WORK_ITEM).stream()
                .map(r -> r.payload().get("team"))
                .distinct())
        .containsExactlyInAnyOrder("Platform", "Payments", "Web");
  }

  @Test
  void each_stream_carries_its_source_system_provenance() {
    assertThat(stream(SimulationDataset.WORK_ITEM)).allMatch(sourceIs(SimulationDataset.SRC_JIRA));
    assertThat(stream(SimulationDataset.TRANSITION)).allMatch(sourceIs(SimulationDataset.SRC_JIRA));
    assertThat(stream(SimulationDataset.PULL_REQUEST))
        .allMatch(sourceIs(SimulationDataset.SRC_SCM));
    assertThat(stream(SimulationDataset.CODE_REVIEW)).allMatch(sourceIs(SimulationDataset.SRC_SCM));
    assertThat(stream(SimulationDataset.BUILD)).allMatch(sourceIs(SimulationDataset.SRC_CI));
    assertThat(stream(SimulationDataset.QUALITY_GATE))
        .allMatch(sourceIs(SimulationDataset.SRC_SONAR));
    // Immutable native id (AD-14) is distinct from the natural key.
    assertThat(records)
        .allMatch(r -> r.externalId().equals(r.sourceSystem() + ":" + r.naturalKey()));
    assertThat(records).allMatch(r -> r.op() == Op.UPSERT && r.fetchKind() == FetchKind.FULL);
    assertThat(records).allMatch(r -> r.sourceInstance().equals("sim"));
  }

  @Test
  void cross_tool_identifiers_link_work_item_to_pr_to_review_build_and_gate() {
    var workItemKeys = keys(stream(SimulationDataset.WORK_ITEM));
    var prKeys = keys(stream(SimulationDataset.PULL_REQUEST));
    var buildKeys = keys(stream(SimulationDataset.BUILD));

    // Every PR names an existing work item; every review/build names an existing PR; every gate
    // names an existing build — the correlator (INC-2) can stitch the full chain.
    assertThat(stream(SimulationDataset.PULL_REQUEST))
        .allMatch(r -> workItemKeys.contains(r.payload().get("workItemKey")));
    assertThat(stream(SimulationDataset.CODE_REVIEW))
        .allMatch(r -> prKeys.contains(r.payload().get("pullRequestKey")));
    assertThat(stream(SimulationDataset.BUILD))
        .allMatch(r -> prKeys.contains(r.payload().get("pullRequestKey")));
    assertThat(stream(SimulationDataset.QUALITY_GATE))
        .allMatch(r -> buildKeys.contains(r.payload().get("buildKey")));
  }

  @Test
  void contains_the_friction_patterns_blocked_review_wait_and_rework() {
    assertThat(stream(SimulationDataset.TRANSITION))
        .anyMatch(r -> "BLOCKED".equals(r.payload().get("toState")));
    assertThat(stream(SimulationDataset.CODE_REVIEW))
        .anyMatch(r -> "CHANGES_REQUESTED".equals(r.payload().get("outcome")));
    // A rework loop returns from review to in-progress.
    assertThat(stream(SimulationDataset.TRANSITION))
        .anyMatch(
            r ->
                "IN_REVIEW".equals(r.payload().get("fromState"))
                    && "IN_PROGRESS".equals(r.payload().get("toState")));
  }

  @Test
  void carries_no_person_identifiers_anywhere() {
    // Anti-surveillance (NFR-071): no author/assignee/reviewer/user fields in any raw payload.
    List<String> forbidden = List.of("author", "assignee", "reviewer", "user", "email", "login");
    assertThat(records)
        .allSatisfy(
            r ->
                assertThat(r.payload().keySet())
                    .noneMatch(k -> forbidden.contains(k.toLowerCase(java.util.Locale.ROOT))));
  }

  @Test
  void is_byte_for_byte_reproducible_across_instances() {
    assertThat(new SimulationDataset().records()).isEqualTo(new SimulationDataset().records());
  }

  @Test
  void connector_emits_the_dataset_through_the_sink() {
    SimulationConnector connector = new SimulationConnector();
    List<RawRecord> captured = new ArrayList<>();
    SyncContext context = () -> captured::add;
    connector.sync(context);

    assertThat(connector.type()).isEqualTo("simulation");
    assertThat(connector.simulation()).isTrue();
    assertThat(captured).isEqualTo(records);
  }

  private static Predicate<RawRecord> sourceIs(String source) {
    return r -> r.sourceSystem().equals(source);
  }

  private static List<String> keys(List<RawRecord> records) {
    return records.stream().map(r -> r.payload().get("key")).collect(Collectors.toList());
  }

  // Sanity that a bad payload map still round-trips a defensive copy.
  @Test
  void payload_is_an_unmodifiable_defensive_copy() {
    RawRecord any = records.get(0);
    Map<String, String> payload = any.payload();
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> payload.put("x", "y"));
  }
}
