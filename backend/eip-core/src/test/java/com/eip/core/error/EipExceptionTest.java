/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EipExceptionTest {

  @Test
  void sealsExactlyTheFourKernelLeaves() {
    // The kernel taxonomy is closed over the tenancy-agnostic leaves only (condition C1).
    assertThat(EipException.class.isSealed()).isTrue();
    assertThat(List.of(EipException.class.getPermittedSubclasses()))
        .containsExactlyInAnyOrder(
            ValidationException.class,
            ResourceNotFoundException.class,
            PermissionDeniedException.class,
            InternalException.class);
  }

  @Test
  void everyLeafIsAnUncheckedEipException() {
    List<EipException> leaves =
        List.of(
            new ValidationException("bad input"),
            new ResourceNotFoundException("missing"),
            new PermissionDeniedException("denied"),
            new InternalException("boom"));

    assertThat(leaves).allSatisfy(e -> assertThat(e).isInstanceOf(RuntimeException.class));
  }

  @Test
  void carriesMessageAndCause() {
    Throwable cause = new IllegalStateException("root");

    assertThat(new ValidationException("v")).hasMessage("v");
    assertThat(new ResourceNotFoundException("r", cause)).hasMessage("r").hasCause(cause);
    assertThat(new PermissionDeniedException("p", cause)).hasMessage("p").hasCause(cause);
    assertThat(new InternalException("i", cause)).hasMessage("i").hasCause(cause);
    assertThat(new ValidationException("v", cause)).hasCause(cause);
    assertThat(new ResourceNotFoundException("r")).hasMessage("r");
    assertThat(new PermissionDeniedException("p")).hasMessage("p");
    assertThat(new InternalException("i")).hasMessage("i");
  }
}
