# ADR-008: Ship Keycloak as the default OIDC provider with pluggable enterprise IdP brokering and a local-accounts fallback

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [SecurityModel](../architecture/SecurityModel.md) (identity, RBAC); `eip-tenancy`, `eip-app` security filter chain; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Demo, eval, and air-gapped installs need a complete authentication story out of the box (D6), while production enterprises expect to broker their existing IdP (AD FS, Azure AD, Okta).

## Decision

Keycloak is the default OIDC provider; it brokers enterprise IdPs where present, and a local-accounts fallback covers the relaxed `local` profile. Identity is OIDC with deny-by-default RBAC.

## Consequences

- **Positive:** a self-contained AuthN story ships with the product (D6); IdP brokering covers the common enterprise providers without custom code; RBAC is uniform regardless of the upstream IdP.
- **Negative:** Keycloak is another service to operate and secure; the local-accounts fallback must be confined to non-production profiles so it never weakens a hardened install.

## Alternatives rejected

- **Building a bespoke authentication service** — rejected: reinventing a security-critical component with worse assurance than a mature OIDC server.
- **Requiring an external enterprise IdP with no bundled provider** — rejected: air-gapped demo/eval installs would have no way to authenticate out of the box (D6).
