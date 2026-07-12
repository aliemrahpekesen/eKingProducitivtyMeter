/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.GetSessionQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the current session's tenant identity — a pure DTO adapter over the {@link
 * GetSessionQuery} port (BackendPlan §2.4).
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

  private final GetSessionQuery session;

  public SessionController(GetSessionQuery session) {
    this.session = session;
  }

  /**
   * Returns the current session's tenant identity.
   *
   * @return the resolved tenant and its organisation name (read under RLS)
   */
  @GetMapping("/session")
  public SessionView session() {
    return session.current();
  }
}
