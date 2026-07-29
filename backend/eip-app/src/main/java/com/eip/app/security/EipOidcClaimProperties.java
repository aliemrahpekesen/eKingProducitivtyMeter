/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * DEPLOYMENT-level override of the {@code oidc}-mode JWT claim names {@link
 * EipTenantClaimValidator} and {@link EipPrincipalFilter} read (SecurityModel §3/§4, DEBT-012
 * residual). Every deployment of this platform gets exactly one enterprise IdP realm, so exactly
 * one claim-mapping configuration — this is a genuine, bounded feature, not a stand-in for real
 * per-TENANT claim-name customization.
 *
 * <p><b>Why not per-tenant:</b> a per-tenant override would need to look up the calling tenant's
 * configured claim names BEFORE it can read the tenant claim itself from the token — a chicken-and-
 * egg problem with no clean resolution short of a second, tenant-agnostic signal (e.g. a fixed
 * per-tenant issuer or audience) to key the lookup on, which this platform does not have in v0.1
 * (one shared realm serves every tenant; {@link EipTenantClaimValidator} exists precisely because
 * the tenant is NOT otherwise derivable pre-authentication). Solving that properly would mean
 * either (a) one OIDC client/issuer per tenant, so the issuer itself selects the mapping — a
 * deployment topology change, not a claim-name property — or (b) trusting an unauthenticated hint
 * (e.g. a request header or path segment) to pick the mapping before the token is validated, which
 * reintroduces exactly the spoofable-tenant-selection hole {@link
 * com.eip.app.tenant.OidcTenantResolver}'s javadoc explains {@code X-EIP-Tenant} is never trusted
 * for. Neither is attempted here; this class solves the honestly-scoped problem (one deployment,
 * one IdP, configurable claim names) and documents the larger one as explicitly out of scope rather
 * than silently pretending {@code tenant-claim}/{@code roles-claim} are per-tenant.
 *
 * <p>{@code rolesClaim} is a dot-separated path into the JWT's claim structure, walked as nested
 * maps down to the final segment, which must hold a JSON array of role names (Keycloak's default
 * {@value #DEFAULT_ROLES_CLAIM} — {@code realm_access: { roles: [...] }} — is two segments; a
 * flatter IdP mapping a top-level array claim is one segment; a client-roles mapping like {@code
 * resource_access.<client>.roles} is three). See {@link EipPrincipalFilter#extractRoleNames}.
 *
 * @param tenantClaim the JWT claim carrying the caller's tenant id (SecurityModel §4); default
 *     {@value EipTenantClaimValidator#TENANT_CLAIM}
 * @param rolesClaim the dot-separated path to the JWT's role-name array; default {@value
 *     #DEFAULT_ROLES_CLAIM}
 */
@ConfigurationProperties(prefix = "eip.security.oidc")
@Validated
public record EipOidcClaimProperties(
    @NotBlank @DefaultValue(EipTenantClaimValidator.TENANT_CLAIM) String tenantClaim,
    @NotBlank @DefaultValue(DEFAULT_ROLES_CLAIM) String rolesClaim) {

  /** Keycloak's default realm-role claim path (SecurityModel §3). */
  public static final String DEFAULT_ROLES_CLAIM = "realm_access.roles";
}
