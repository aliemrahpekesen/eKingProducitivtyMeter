import type { ReactNode } from 'react';
import { useSession } from '../api/hooks';
import { useTenant } from '../app/tenantContext';

/**
 * Gates a control on the RBAC permission (wire id, e.g. `"tenant.manage"`) the caller must hold to
 * invoke the endpoint the control triggers — the frontend half of the backend's `x-eip-permission`
 * OpenAPI extension (APIDesign §10, DEBT-012; see `OpenApiConfig.permissionExtensionCustomizer`).
 *
 * Wired (DEBT-012 residual): `GET /api/v1/session` now returns `effectivePermissions` — the SAME
 * override-adjusted set `PermissionEnforcementInterceptor` enforces (`SessionController`). This reads
 * it from the already-fetched, React-Query-cached session (keyed by tenant, so no extra request) and
 * renders `children` only when that set contains `permission`.
 *
 * Fail-open is deliberate and narrow: when the permission list is genuinely absent — session not yet
 * loaded, no tenant selected (query disabled), or the request errored — `children` render rather than
 * flicker/vanish. The backend interceptor is the actual enforcement boundary (a caller lacking the
 * permission still gets a 403 on the real request); this gate is a UX layer, never the security
 * control. Once the list is present it is honored exactly.
 */
export function Can({
  permission,
  children,
}: {
  /** The permission's stable wire id (`Permission.wireId()`), e.g. `"connector.configure"`. */
  permission: string;
  children: ReactNode;
}): JSX.Element | null {
  const { tenantId } = useTenant();
  const permissions = useSession(tenantId).data?.effectivePermissions;
  // Genuinely absent (undefined) → fail open; present → honor it exactly.
  if (permissions === undefined) {
    return <>{children}</>;
  }
  return permissions.includes(permission) ? <>{children}</> : null;
}
