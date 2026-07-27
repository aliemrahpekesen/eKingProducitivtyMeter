/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.simulation;

import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed, deterministic simulation dataset (TASK-0016). Produces one flat, ordered list of
 * {@link RawRecord}s spanning six source streams for three teams, with explicit cross-tool
 * identifiers so the downstream correlator can stitch work item → pull request → review → build →
 * quality gate.
 *
 * <p>The three teams deliberately exhibit different flow shapes so the computed friction ranks
 * them:
 *
 * <ul>
 *   <li><b>Platform</b> — worst: long blocked time, long review waits, and a rework loop
 *       (changes-requested → back to in-progress → re-review).
 *   <li><b>Payments</b> — middling: one blocked item and one moderate review wait.
 *   <li><b>Web</b> — clean flow: short review waits, no blocking, no rework.
 * </ul>
 *
 * <p>All timestamps are {@link #BASE} plus a whole-hour offset — no clock read, no randomness — so
 * the records (and every metric derived from them) are byte-for-byte reproducible across replays.
 * Payloads carry no person identifiers: reviews and work items are anti-surveillance by
 * construction (NFR-071), attributing activity to artifacts and teams only.
 */
public final class SimulationDataset {

  /** Fixed anchor instant; every payload timestamp is this plus a whole-hour offset. */
  public static final Instant BASE = Instant.parse("2026-01-05T09:00:00Z");

  // Streams (select the normalizer downstream).
  static final String WORK_ITEM = "work_item";
  static final String TRANSITION = "work_item_transition";
  static final String PULL_REQUEST = "pull_request";
  static final String CODE_REVIEW = "code_review";
  static final String BUILD = "build";
  static final String QUALITY_GATE = "quality_gate";

  // Provenance systems of record.
  static final String SRC_JIRA = "jira";
  static final String SRC_SCM = "bitbucket";
  static final String SRC_CI = "ci";
  static final String SRC_SONAR = "sonarqube";

  private static final String INSTANCE = "sim";

  // Workflow states used across the dataset.
  private static final String TODO = "TODO";
  private static final String IN_PROGRESS = "IN_PROGRESS";
  private static final String BLOCKED = "BLOCKED";
  private static final String IN_REVIEW = "IN_REVIEW";
  private static final String DONE = "DONE";

  /**
   * Returns the full dataset in a fixed emission order (per team, per item: work item, transitions,
   * pull request, reviews, build, quality gate).
   *
   * @return the ordered, deterministic list of raw records
   */
  public List<RawRecord> records() {
    List<RawRecord> out = new ArrayList<>();
    for (ItemSpec item : items()) {
      emitItem(out, item);
    }
    return List.copyOf(out);
  }

  private void emitItem(List<RawRecord> out, ItemSpec item) {
    int createdH = item.stages().get(0).hour();
    int resolvedH = item.stages().get(item.stages().size() - 1).hour();

    out.add(
        raw(
            WORK_ITEM,
            SRC_JIRA,
            item.key(),
            payload(
                "key", item.key(),
                "team", item.team(),
                "type", item.type(),
                "title", item.title(),
                "status", DONE,
                "createdAt", at(createdH),
                "resolvedAt", at(resolvedH))));

    List<Stage> stages = item.stages();
    for (int i = 1; i < stages.size(); i++) {
      Stage from = stages.get(i - 1);
      Stage to = stages.get(i);
      out.add(
          raw(
              TRANSITION,
              SRC_JIRA,
              item.key() + "#" + i,
              payload(
                  "workItemKey", item.key(),
                  "seq", Integer.toString(i),
                  "fromState", from.state(),
                  "toState", to.state(),
                  "at", at(to.hour()))));
    }

    out.add(
        raw(
            PULL_REQUEST,
            SRC_SCM,
            item.prKey(),
            payload(
                "key", item.prKey(),
                "workItemKey", item.key(),
                "team", item.team(),
                "title", item.title(),
                "sourceBranch", "feature/" + item.key(),
                "status", "MERGED",
                "createdAt", at(item.prCreatedH()),
                "mergedAt", at(item.prMergedH()))));

    for (ReviewSpec review : item.reviews()) {
      out.add(
          raw(
              CODE_REVIEW,
              SRC_SCM,
              review.key(),
              payload(
                  "key", review.key(),
                  "pullRequestKey", item.prKey(),
                  "workItemKey", item.key(),
                  "outcome", review.outcome(),
                  "requestedAt", at(review.requestedH()),
                  "completedAt", at(review.completedH()))));
    }

    out.add(
        raw(
            BUILD,
            SRC_CI,
            item.buildKey(),
            payload(
                "key", item.buildKey(),
                "pullRequestKey", item.prKey(),
                "workItemKey", item.key(),
                "status", item.buildStatus(),
                "startedAt", at(item.buildStartH()),
                "finishedAt", at(item.buildFinishH()))));

    out.add(
        raw(
            QUALITY_GATE,
            SRC_SONAR,
            item.gateKey(),
            payload(
                "key", item.gateKey(),
                "pullRequestKey", item.prKey(),
                "buildKey", item.buildKey(),
                "workItemKey", item.key(),
                "status", item.gateStatus(),
                "evaluatedAt", at(item.gateEvalH()))));
  }

  private static RawRecord raw(
      String stream, String sourceSystem, String naturalKey, Map<String, String> payload) {
    return RawRecord.ofFlat(
        stream,
        naturalKey,
        sourceSystem,
        INSTANCE,
        sourceSystem
            + ":"
            + naturalKey, // immutable native id (AD-14), distinct from the natural key
        Op.UPSERT,
        FetchKind.FULL,
        payload);
  }

  private static String at(int hourOffset) {
    return BASE.plus(Duration.ofHours(hourOffset)).toString();
  }

  private static Map<String, String> payload(String... kv) {
    if (kv.length % 2 != 0) {
      throw new IllegalArgumentException("payload requires key/value pairs");
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }

  // --- the fixed source data -------------------------------------------------

  private List<ItemSpec> items() {
    return List.of(
        // Platform — worst: blocked-heavy item with a rework loop, plus a long review wait.
        new ItemSpec(
            "PLAT-101",
            "Platform",
            "story",
            "Checkout service refactor",
            List.of(
                stage(TODO, 0),
                stage(IN_PROGRESS, 2),
                stage(BLOCKED, 4),
                stage(IN_PROGRESS, 28), // unblocked after 24h
                stage(IN_REVIEW, 30),
                stage(IN_PROGRESS, 54), // changes requested after 24h review wait — rework
                stage(IN_REVIEW, 56),
                stage(DONE, 60)),
            "PR-101",
            30,
            60,
            List.of(
                new ReviewSpec("REV-101", 30, 54, "CHANGES_REQUESTED"),
                new ReviewSpec("REV-101B", 56, 59, "APPROVED")),
            "BUILD-101",
            "SUCCESS",
            56,
            57,
            "QG-101",
            "PASSED",
            57),
        new ItemSpec(
            "PLAT-102",
            "Platform",
            "bug",
            "Checkout latency regression",
            List.of(stage(TODO, 0), stage(IN_PROGRESS, 1), stage(IN_REVIEW, 5), stage(DONE, 29)),
            "PR-102",
            5,
            29,
            List.of(new ReviewSpec("REV-102", 5, 28, "APPROVED")), // 23h review wait
            "BUILD-102",
            "SUCCESS",
            28,
            29,
            "QG-102",
            "PASSED",
            29),
        new ItemSpec(
            "PLAT-103",
            "Platform",
            "story",
            "Checkout config screen",
            List.of(stage(TODO, 0), stage(IN_PROGRESS, 2), stage(IN_REVIEW, 6), stage(DONE, 10)),
            "PR-103",
            6,
            10,
            List.of(new ReviewSpec("REV-103", 6, 9, "APPROVED")),
            "BUILD-103",
            "SUCCESS",
            9,
            10,
            "QG-103",
            "PASSED",
            10),
        // Payments — middling: one blocked item, one moderate review wait.
        new ItemSpec(
            "PAY-201",
            "Payments",
            "story",
            "Refund flow",
            List.of(stage(TODO, 0), stage(IN_PROGRESS, 2), stage(IN_REVIEW, 6), stage(DONE, 14)),
            "PR-201",
            6,
            14,
            List.of(new ReviewSpec("REV-201", 6, 13, "APPROVED")), // 7h review wait
            "BUILD-201",
            "SUCCESS",
            13,
            14,
            "QG-201",
            "PASSED",
            14),
        new ItemSpec(
            "PAY-202",
            "Payments",
            "bug",
            "Webhook retry storm",
            List.of(
                stage(TODO, 0),
                stage(IN_PROGRESS, 1),
                stage(BLOCKED, 3),
                stage(IN_PROGRESS, 7), // blocked 4h
                stage(IN_REVIEW, 9),
                stage(DONE, 13)),
            "PR-202",
            9,
            13,
            List.of(new ReviewSpec("REV-202", 9, 12, "APPROVED")),
            "BUILD-202",
            "SUCCESS",
            12,
            13,
            "QG-202",
            "PASSED",
            13),
        new ItemSpec(
            "PAY-203",
            "Payments",
            "story",
            "Ledger export",
            List.of(stage(TODO, 0), stage(IN_PROGRESS, 2), stage(IN_REVIEW, 5), stage(DONE, 9)),
            "PR-203",
            5,
            9,
            List.of(new ReviewSpec("REV-203", 5, 8, "APPROVED")),
            "BUILD-203",
            "SUCCESS",
            8,
            9,
            "QG-203",
            "PASSED",
            9),
        // Web — clean flow: short review waits, no blocking, no rework.
        new ItemSpec(
            "WEB-301",
            "Web",
            "story",
            "Navigation redesign",
            List.of(stage(TODO, 0), stage(IN_PROGRESS, 1), stage(IN_REVIEW, 3), stage(DONE, 6)),
            "PR-301",
            3,
            6,
            List.of(new ReviewSpec("REV-301", 3, 5, "APPROVED")),
            "BUILD-301",
            "SUCCESS",
            5,
            6,
            "QG-301",
            "PASSED",
            6),
        new ItemSpec(
            "WEB-302",
            "Web",
            "bug",
            "Form validation edge case",
            List.of(stage(TODO, 0), stage(IN_PROGRESS, 1), stage(IN_REVIEW, 2), stage(DONE, 5)),
            "PR-302",
            2,
            5,
            List.of(new ReviewSpec("REV-302", 2, 4, "APPROVED")),
            "BUILD-302",
            "SUCCESS",
            4,
            5,
            "QG-302",
            "PASSED",
            5),
        new ItemSpec(
            "WEB-303",
            "Web",
            "story",
            "Footer links",
            List.of(stage(TODO, 0), stage(IN_PROGRESS, 2), stage(IN_REVIEW, 4), stage(DONE, 7)),
            "PR-303",
            4,
            7,
            List.of(new ReviewSpec("REV-303", 4, 6, "APPROVED")),
            "BUILD-303",
            "SUCCESS",
            6,
            7,
            "QG-303",
            "PASSED",
            7));
  }

  private static Stage stage(String state, int hour) {
    return new Stage(state, hour);
  }

  private record Stage(String state, int hour) {}

  private record ReviewSpec(String key, int requestedH, int completedH, String outcome) {}

  private record ItemSpec(
      String key,
      String team,
      String type,
      String title,
      List<Stage> stages,
      String prKey,
      int prCreatedH,
      int prMergedH,
      List<ReviewSpec> reviews,
      String buildKey,
      String buildStatus,
      int buildStartH,
      int buildFinishH,
      String gateKey,
      String gateStatus,
      int gateEvalH) {}
}
