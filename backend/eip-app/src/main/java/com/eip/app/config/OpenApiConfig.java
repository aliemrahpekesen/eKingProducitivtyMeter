/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.app.security.PermissionExempt;
import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.rbac.Permission;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

/**
 * Names the generated OpenAPI 3 contract for the {@code /api/v1} surface and documents its error
 * responses (DEBT-011). Deterministic metadata (fixed title/version) so the committed contract
 * snapshot is stable across builds — no host, date, or build-number drift.
 *
 * <p><b>Error-response documentation (DEBT-011 item 4):</b> a single reusable {@code ProblemDetail}
 * schema component (RFC 7807, matching {@code ApiExceptionHandler}'s actual output shape) plus one
 * {@link OperationCustomizer} bean that adds the standard error-response set to every operation,
 * inferred from the same annotations {@code PermissionEnforcementInterceptor} already reads — a
 * global customizer, not ~15 controllers' worth of repeated {@code @ApiResponse} blocks, since the
 * standard set (400/401/403/404) is near-universal across this surface and springdoc had no partial
 * per-endpoint annotations to build on. The handful of endpoint-specific outcomes {@code
 * ApiExceptionHandler} maps beyond that set (409 {@code ai-disabled}, 502 {@code
 * ai-upstream}/{@code ai-rejected}/{@code source-sync}, the webhook trigger's bespoke 202/401/404/
 * 429) are annotated directly on their controller methods with {@code @ApiResponse}, which
 * springdoc merges with (and can override) this customizer's additions.
 */
@Configuration
public class OpenApiConfig {

  /** The shared {@code application/problem+json} schema component name (DEBT-011). */
  public static final String PROBLEM_SCHEMA = "ProblemDetail";

  /**
   * The custom OpenAPI extension name that carries an operation's required permission(s) (APIDesign
   * §10, DEBT-012 residual) — drives the frontend {@code <Can>} gate.
   */
  public static final String PERMISSION_EXTENSION = "x-eip-permission";

  /**
   * Provides the OpenAPI document metadata plus the shared {@value #PROBLEM_SCHEMA} schema
   * component every error response in this API references.
   *
   * @return the OpenAPI definition with EIP title, the {@code v1} API version, and the problem+json
   *     schema component
   */
  @Bean
  public OpenAPI eipOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Engineering Intelligence Platform API")
                .version("v1")
                .description(
                    "REST /api/v1 surface. Team-level engineering intelligence; "
                        + "no individual developer metrics (NFR-071)."))
        .components(new Components().addSchemas(PROBLEM_SCHEMA, problemDetailSchema()));
  }

  /**
   * Adds the standard error-response set (400/401/403/404) to every operation whose handler method
   * carries the annotations that make each response reachable, without repeating an
   * {@code @ApiResponse} block on every controller method.
   *
   * @return the operation customizer
   */
  @Bean
  public OperationCustomizer standardErrorResponsesCustomizer() {
    return (operation, handlerMethod) -> {
      boolean exempt = handlerMethod.getMethodAnnotation(PermissionExempt.class) != null;
      boolean requiresPermission =
          handlerMethod.getMethodAnnotation(RequiresPermission.class) != null;
      if (acceptsClientInput(handlerMethod)) {
        addIfAbsent(
            operation,
            "400",
            problemResponse(
                "Request validation failed (bad param, cursor, or sort).",
                "/problems/validation",
                400));
      }
      if (requiresPermission && !exempt) {
        addIfAbsent(
            operation,
            "401",
            problemResponse(
                "No tenant/caller could be authenticated.", "/problems/unauthenticated", 401));
        addIfAbsent(
            operation,
            "403",
            problemResponse(
                "The caller lacks the permission this endpoint requires.",
                "/problems/permission-denied",
                403));
      }
      if (hasPathVariable(handlerMethod)) {
        addIfAbsent(
            operation,
            "404",
            problemResponse("The requested resource does not exist.", "/problems/not-found", 404));
      }
      return operation;
    };
  }

  /**
   * Adds the {@value #PERMISSION_EXTENSION} extension (APIDesign §10, DEBT-012 residual) to every
   * operation whose handler method carries {@link RequiresPermission}, so the generated OpenAPI
   * contract — and the frontend {@code <Can>} gate that reads it — knows which permission(s)
   * satisfy the endpoint without duplicating the annotation's declaration by hand. A separate bean
   * from {@link #standardErrorResponsesCustomizer()}: springdoc applies every registered {@code
   * OperationCustomizer} bean, so there is no need to fold unrelated concerns into one method.
   * Operations with {@link PermissionExempt} or neither annotation get no extension — there is
   * nothing to gate on.
   *
   * @return the operation customizer
   */
  @Bean
  public OperationCustomizer permissionExtensionCustomizer() {
    return (operation, handlerMethod) -> {
      RequiresPermission requiresPermission =
          handlerMethod.getMethodAnnotation(RequiresPermission.class);
      if (requiresPermission != null) {
        // ArrayList, not List.of(): mirrors the LinkedHashMap reasoning below (DEBT-011) — an
        // any-of permission list is meaningful order (the annotation's declared order), and the
        // committed OpenAPI snapshot must be byte-stable across JVM runs. List.of() itself always
        // preserves insertion order (unlike Map.of()/Set.of()), but ArrayList keeps this extension
        // symmetric with the rest of this class's collection choices and its rationale.
        List<String> wireIds = new ArrayList<>(requiresPermission.value().length);
        for (Permission permission : requiresPermission.value()) {
          wireIds.add(permission.wireId());
        }
        operation.addExtension(PERMISSION_EXTENSION, wireIds);
      }
      return operation;
    };
  }

  private static boolean acceptsClientInput(HandlerMethod handlerMethod) {
    for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
      if (parameter.hasParameterAnnotation(RequestParam.class)
          || parameter.hasParameterAnnotation(PathVariable.class)
          || parameter.hasParameterAnnotation(RequestBody.class)
          || parameter.hasParameterAnnotation(RequestHeader.class)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasPathVariable(HandlerMethod handlerMethod) {
    for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
      if (parameter.hasParameterAnnotation(PathVariable.class)) {
        return true;
      }
    }
    return false;
  }

  private static void addIfAbsent(Operation operation, String status, ApiResponse response) {
    if (operation.getResponses() == null) {
      operation.setResponses(new ApiResponses());
    }
    if (!operation.getResponses().containsKey(status)) {
      operation.getResponses().addApiResponse(status, response);
    }
  }

  /**
   * Builds a {@code application/problem+json} response referencing the shared {@value
   * #PROBLEM_SCHEMA} schema, with a representative example for this specific {@code type}.
   *
   * @param description the human-readable response description
   * @param problemType the RFC 7807 {@code type} URI (BackendPlan §10 {@code /problems/*} taxonomy)
   * @param status the HTTP status this response represents (mirrored into the example body)
   * @return the reusable problem+json {@code ApiResponse}
   */
  public static ApiResponse problemResponse(String description, String problemType, int status) {
    // LinkedHashMap, not Map.of(): Map.of()'s iteration order for 2+ entries is randomized per JVM
    // run (a hash-flooding mitigation), which would make the generated OpenAPI snapshot's example
    // key order vary between builds -- silently breaking the additive-only diff gate (DEBT-011)
    // every time CI happens to start a JVM with a different random seed than the one that last
    // regenerated the committed file. A LinkedHashMap's order is exactly the insertion order below,
    // every run, on every JVM.
    Map<String, Object> exampleValue = new java.util.LinkedHashMap<>();
    exampleValue.put("type", problemType);
    exampleValue.put("title", description);
    exampleValue.put("status", status);
    Example example = new Example().value(exampleValue);
    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    "application/problem+json",
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))
                        .addExamples(problemType, example)));
  }

  private static Schema<?> problemDetailSchema() {
    return new Schema<>()
        .type("object")
        .addProperty("type", new Schema<>().type("string").format("uri"))
        .addProperty("title", new Schema<>().type("string"))
        .addProperty("status", new Schema<>().type("integer"))
        .addProperty("detail", new Schema<>().type("string"))
        .addProperty("instance", new Schema<>().type("string").format("uri"))
        .addProperty(
            "traceId",
            new Schema<>()
                .type("string")
                .description("OpenTelemetry trace id of the request that produced this error."));
  }
}
