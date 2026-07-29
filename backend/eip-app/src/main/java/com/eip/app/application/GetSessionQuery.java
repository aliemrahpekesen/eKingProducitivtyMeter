/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.api.SessionView;

/**
 * Resolves the current session's tenant identity (the resolved tenant plus its organisation name,
 * read under RLS). Fails closed when no tenant is bound to the calling thread.
 */
public interface GetSessionQuery {

  /**
   * Returns the current session's tenant identity.
   *
   * @return the session view
   */
  SessionView current();
}
