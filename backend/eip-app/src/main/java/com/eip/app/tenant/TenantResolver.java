/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

import com.eip.tenancy.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Resolves the tenant a request executes as. The dev implementation reads a header; the SPRINT-02
 * implementation derives it from the validated OIDC token — the interface is the stable seam so
 * swapping the source never touches the filter or the RLS binding.
 */
public interface TenantResolver {

  /**
   * Resolves the tenant for the given request.
   *
   * @param request the inbound request
   * @return the resolved tenant, or empty when none can be determined
   */
  Optional<TenantContext> resolve(HttpServletRequest request);
}
