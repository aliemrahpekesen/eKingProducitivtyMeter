/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.ai.api.AiPolicyView;
import com.eip.ai.api.GetAiStatusQuery;
import com.eip.ai.api.ManageAiPolicyUseCase;
import com.eip.ai.api.ManageAiPolicyUseCase.UpdateAiPolicyCommand;
import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.rbac.Permission;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI explanation-layer policy surface (ADR-024). A pure DTO adapter (BackendPlan §2.4): every
 * endpoint delegates to the {@code eip-ai} module's ports. {@code /ai/policy} is management-only
 * ({@link Permission#AI_POLICY_MANAGE}, TENANT_ADMIN in v0.1) and never returns the stored secret's
 * plaintext; {@code /ai/status} is a minimal, dashboard-view-gated signal for whether the layer is
 * on at all, so a client can decide whether to offer AI actions without needing policy-management
 * permission.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiPolicyController {

  private final ManageAiPolicyUseCase policy;
  private final GetAiStatusQuery status;

  /**
   * Creates the controller.
   *
   * @param policy the policy management port
   * @param status the status query port
   */
  public AiPolicyController(ManageAiPolicyUseCase policy, GetAiStatusQuery status) {
    this.policy = policy;
    this.status = status;
  }

  /**
   * Reads the current tenant's AI policy.
   *
   * @return the current policy
   */
  @GetMapping("/policy")
  @RequiresPermission(Permission.AI_POLICY_MANAGE)
  public AiPolicyView get() {
    return policy.get();
  }

  /**
   * Updates the current tenant's AI policy.
   *
   * @param request the update payload
   * @return the policy after the update
   */
  @PutMapping("/policy")
  @RequiresPermission(Permission.AI_POLICY_MANAGE)
  public AiPolicyView update(@RequestBody UpdateAiPolicyRequest request) {
    return policy.update(
        new UpdateAiPolicyCommand(
            request.enabled(),
            request.provider(),
            request.baseUrl(),
            request.model(),
            request.secret(),
            request.temperature(),
            request.maxTokens()));
  }

  /**
   * Reads whether the AI explanation layer is enabled for the current tenant.
   *
   * @return the status payload
   */
  @GetMapping("/status")
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  public AiStatusResponse status() {
    return new AiStatusResponse(status.enabled());
  }

  /**
   * AI policy update payload; every field but {@code enabled} is optional (null = keep current —
   * see {@link UpdateAiPolicyCommand}).
   *
   * @param enabled whether the layer should be turned on
   * @param provider the provider to configure, or {@code null} to keep the current value
   * @param baseUrl the provider base URL, or {@code null} to keep the current value
   * @param model the model identifier, or {@code null} to keep the current value
   * @param secret a new provider API key/token to store, or {@code null} to keep the current one
   * @param temperature the sampling temperature, or {@code null} to keep the current value
   * @param maxTokens the maximum generation length in tokens, or {@code null} to keep the current
   *     value
   */
  public record UpdateAiPolicyRequest(
      boolean enabled,
      @Nullable String provider,
      @Nullable String baseUrl,
      @Nullable String model,
      @Nullable String secret,
      @Nullable Double temperature,
      @Nullable Integer maxTokens) {}

  /**
   * The AI status payload.
   *
   * @param enabled whether the AI explanation layer is enabled for the current tenant
   */
  public record AiStatusResponse(boolean enabled) {}
}
