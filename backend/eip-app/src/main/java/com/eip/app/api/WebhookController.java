/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.TriggerWebhookSyncUseCase;
import com.eip.app.application.TriggerWebhookSyncUseCase.WebhookOutcome;
import com.eip.app.config.OpenApiConfig;
import com.eip.app.security.PermissionExempt;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook TRIGGER endpoint, not a data path: the body (if any) is read here but never parsed or
 * forwarded — data always flows through the connector's own authenticated incremental fetch,
 * dispatched by {@link TriggerWebhookSyncUseCase} once the {@code X-EIP-Webhook-Token} header is
 * validated (SecurityModel — single-sourced, idempotent; freshness comes from that immediate sync,
 * not from the webhook payload). A pure DTO adapter (BackendPlan §2.4): tenant binding, token
 * comparison, debounce, brute-force throttling, and dispatch all live in {@link
 * TriggerWebhookSyncUseCase}. A connector over its rejected-attempt threshold reports 429 for EVERY
 * attempt — including one bearing the correct token — until its window rolls off (M2b Wave 3E; see
 * that interface's class javadoc). {@code @PermissionExempt}: this endpoint authenticates itself
 * via {@code X-EIP-Webhook-Token}, not RBAC — it is on the deny-by-default whitelist (SecurityModel
 * §4), permitAll at the security-filter layer in both {@code eip.security.mode}s.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

  private static final String TOKEN_HEADER = "X-EIP-Webhook-Token";
  private static final String PROBLEM_REF = "#/components/schemas/" + OpenApiConfig.PROBLEM_SCHEMA;

  private final TriggerWebhookSyncUseCase webhooks;

  public WebhookController(TriggerWebhookSyncUseCase webhooks) {
    this.webhooks = webhooks;
  }

  /**
   * Triggers an incremental sync for one connector.
   *
   * @param tenantId the tenant the path names (external callers cannot set {@code X-EIP-Tenant})
   * @param connectorId the target connector
   * @param token the {@code X-EIP-Webhook-Token} header value, or absent
   * @param ignoredBody the request body, read but never parsed or forwarded (see class javadoc)
   * @return 202 (accepted or debounced), 401 (missing/invalid token), 404 (unknown connector), or
   *     429 (connector currently throttled by the brute-force guard)
   */
  @PostMapping("/{tenantId}/{connectorId}")
  @PermissionExempt
  @ApiResponses({
    @ApiResponse(
        responseCode = "202",
        description = "Accepted and dispatched, or debounced (a trigger arrived moments ago).",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = WebhookStatusView.class))),
    @ApiResponse(
        responseCode = "401",
        description = "The X-EIP-Webhook-Token header is missing or does not match the connector.",
        content =
            @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM_REF))),
    @ApiResponse(
        responseCode = "404",
        description = "No connector with this id exists for this tenant.",
        content =
            @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM_REF))),
    @ApiResponse(
        responseCode = "429",
        description = "This connector is throttled after too many rejected attempts; retry later.",
        content =
            @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM_REF))),
  })
  public ResponseEntity<Object> trigger(
      @PathVariable UUID tenantId,
      @PathVariable UUID connectorId,
      @RequestHeader(name = TOKEN_HEADER, required = false) @Nullable String token,
      @RequestBody(required = false) @Nullable String ignoredBody) {
    WebhookOutcome outcome = webhooks.trigger(tenantId, connectorId, token);
    return switch (outcome) {
      case ACCEPTED ->
          ResponseEntity.status(HttpStatus.ACCEPTED).body(new WebhookStatusView("accepted"));
      case DEBOUNCED ->
          ResponseEntity.status(HttpStatus.ACCEPTED).body(new WebhookStatusView("debounced"));
      case UNAUTHORIZED ->
          ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(
                  ProblemDetail.forStatusAndDetail(
                      HttpStatus.UNAUTHORIZED, "missing or invalid webhook token"));
      case THROTTLED ->
          ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
              .body(
                  ProblemDetail.forStatusAndDetail(
                      HttpStatus.TOO_MANY_REQUESTS,
                      "too many rejected webhook attempts for this connector; retry later"));
      case NOT_FOUND ->
          ResponseEntity.status(HttpStatus.NOT_FOUND)
              .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "connector not found"));
    };
  }

  /**
   * The webhook trigger's minimal acceptance body.
   *
   * @param status {@code accepted} or {@code debounced}
   */
  public record WebhookStatusView(String status) {}
}
