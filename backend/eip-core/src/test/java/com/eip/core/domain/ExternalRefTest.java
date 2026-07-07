/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalRefTest {

  private static final UUID ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
  private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-000000000002");
  private static final UUID ENTITY = UUID.fromString("018f0000-0000-7000-8000-000000000003");
  private static final Instant SEEN = Instant.parse("2026-07-07T10:15:30Z");

  private static ExternalRef sample(List<String> aliases) {
    return new ExternalRef(
        ID,
        TENANT,
        EntityType.WORK_ITEM,
        ENTITY,
        "jira",
        "jira-cloud-1",
        "10001",
        "PROJ-1234",
        aliases,
        "https://jira.example/browse/PROJ-1234",
        SEEN);
  }

  @Test
  void keepsImmutableExternalIdDistinctFromMutableExternalKey() {
    // given / when
    ExternalRef ref = sample(List.of("OLD-1234"));

    // then — AD-14: externalId is the native immutable id; externalKey is the renamable human key
    assertThat(ref.externalId()).isEqualTo("10001");
    assertThat(ref.externalKey()).isEqualTo("PROJ-1234");
    assertThat(ref.externalId()).isNotEqualTo(ref.externalKey());
    assertThat(ref.keyAliases()).containsExactly("OLD-1234");
  }

  @Test
  void exposesAllProvenanceAttributes() {
    ExternalRef ref = sample(List.of());

    assertThat(ref.id()).isEqualTo(ID);
    assertThat(ref.tenantId()).isEqualTo(TENANT);
    assertThat(ref.entityType()).isEqualTo(EntityType.WORK_ITEM);
    assertThat(ref.entityId()).isEqualTo(ENTITY);
    assertThat(ref.sourceSystem()).isEqualTo("jira");
    assertThat(ref.sourceInstance()).isEqualTo("jira-cloud-1");
    assertThat(ref.url()).isEqualTo("https://jira.example/browse/PROJ-1234");
    assertThat(ref.lastSeenAt()).isEqualTo(SEEN);
  }

  @Test
  void defensivelyCopiesKeyAliasesSoLaterMutationDoesNotLeakIn() {
    // given
    List<String> mutable = new ArrayList<>(List.of("OLD-1"));
    ExternalRef ref = sample(mutable);

    // when
    mutable.add("SNEAKY-2");

    // then — the record holds an immutable copy taken at construction
    assertThat(ref.keyAliases()).containsExactly("OLD-1");
    assertThatThrownBy(() -> ref.keyAliases().add("X"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void allowsNullOptionalFields() {
    ExternalRef ref =
        new ExternalRef(
            ID,
            TENANT,
            EntityType.PULL_REQUEST,
            ENTITY,
            "github",
            "gh-1",
            "node-9",
            null,
            List.of(),
            null,
            SEEN);

    assertThat(ref.externalKey()).isNull();
    assertThat(ref.url()).isNull();
  }

  @Test
  @SuppressWarnings("NullAway") // deliberately passes null to verify the non-null contract
  void rejectsNullRequiredFields() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new ExternalRef(
                    null,
                    TENANT,
                    EntityType.WORK_ITEM,
                    ENTITY,
                    "jira",
                    "jira-1",
                    "1",
                    null,
                    List.of(),
                    null,
                    SEEN))
        .withMessageContaining("id");
  }

  @Test
  void rejectsBlankSourceSystem() {
    assertThatThrownBy(
            () ->
                new ExternalRef(
                    ID,
                    TENANT,
                    EntityType.WORK_ITEM,
                    ENTITY,
                    " ",
                    "jira-1",
                    "1",
                    null,
                    List.of(),
                    null,
                    SEEN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceSystem");
  }

  @Test
  void honoursValueEquality() {
    ExternalRef a = sample(List.of("OLD-1234"));
    ExternalRef b = sample(List.of("OLD-1234"));

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a.toString()).contains("jira", "PROJ-1234");
  }
}
