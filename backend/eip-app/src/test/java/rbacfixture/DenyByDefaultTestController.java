/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package rbacfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately unannotated {@code /api/v1} test-only endpoint (no {@code @RequiresPermission}, no
 * {@code @PermissionExempt}) used by {@code com.eip.app.OidcRbacIntegrationTest} to prove
 * deny-by-default (SecurityModel §4, FR-122): an endpoint that declares no permission is refused,
 * never silently permitted.
 *
 * <p>Deliberately placed OUTSIDE the {@code com.eip} package tree (not {@code com.eip.*}) and wired
 * only via {@code @Import} on that one test class — never component-scanned into the main
 * application context — so it cannot appear in {@code ApplicationModularityTests}' {@code
 * com.eip}-scoped Spring Modulith verification or in any other test's context.
 */
@RestController
@RequestMapping("/api/v1")
public class DenyByDefaultTestController {

  /**
   * An endpoint with no permission declaration at all.
   *
   * @return a body that must never be reachable — deny-by-default is expected to refuse first
   */
  @GetMapping("/test-only/unannotated")
  public String value() {
    return "unreachable-if-deny-by-default-works";
  }
}
