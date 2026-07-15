// Minimal fetch client for the /api/v1 surface. Two auth modes, switched at runtime by
// <AuthProvider> after GET /api/v1/session/auth resolves (see ../auth/AuthProvider.tsx):
//   - HEADER (default, today's behavior): sends the dev tenant header (X-EIP-Tenant — the backend's
//     dev resolver). Zero change from pre-OIDC behavior.
//   - OIDC: attaches `Authorization: Bearer <access_token>` and never sends X-EIP-Tenant (the
//     backend derives the tenant from the token's claim and ignores the header).
// Surfaces RFC 7807 problem+json errors (title/detail/status/traceId) so the UI can render honest
// error states.
import type { AuthConfigView } from './types';

/** RFC 7807 problem+json body (subset the backend returns). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  traceId?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem?: ProblemDetail;

  constructor(status: number, problem?: ProblemDetail) {
    super(problem?.title ?? `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

/** Dev tenant header (backend HeaderTenantResolver) — HEADER mode only. */
export const TENANT_HEADER = 'X-EIP-Tenant';

const BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export type AuthMode = 'HEADER' | 'OIDC';

let authMode: AuthMode = 'HEADER';
let accessTokenGetter: () => string | null = () => null;
let authExpiredHandler: () => void = () => {};

/** Set once by <AuthProvider> after GET /api/v1/session/auth resolves. Defaults to HEADER so any
 * code path that runs before boot (or in tests that don't set it) keeps today's behavior. */
export function setAuthMode(mode: AuthMode): void {
  authMode = mode;
}

/** Injectable token source — avoids threading the access token through every hook signature. */
export function setAccessTokenGetter(getter: () => string | null): void {
  accessTokenGetter = getter;
}

/** <AuthProvider> listens for this to show the login screen again after a 401 in OIDC mode. */
export function setAuthExpiredHandler(handler: () => void): void {
  authExpiredHandler = handler;
}

/**
 * Auth headers for the given mode. `omitEmptyTenant` matches apiPost's pre-existing HEADER-mode
 * behavior (skip the header entirely for platform endpoints called with an empty tenantId) — apiGet
 * / apiGetText always sent the header, even empty, so they omit this flag to stay byte-identical.
 */
function authHeaders(tenantId: string, omitEmptyTenant = false): Record<string, string> {
  if (authMode === 'OIDC') {
    const token = accessTokenGetter();
    return token !== null ? { Authorization: `Bearer ${token}` } : {};
  }
  if (omitEmptyTenant && tenantId.length === 0) {
    return {};
  }
  return { [TENANT_HEADER]: tenantId };
}

function reportIfUnauthorized(status: number): void {
  if (authMode === 'OIDC' && status === 401) {
    authExpiredHandler();
  }
}

/** GET /api/v1/session/auth — no auth, no tenant header, called once at app boot by <AuthProvider>. */
export async function getAuthConfig(): Promise<AuthConfigView> {
  const response = await fetch(`${BASE}/api/v1/session/auth`, {
    headers: { Accept: 'application/json, application/problem+json' },
  });
  if (!response.ok) {
    let problem: ProblemDetail | undefined;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Non-JSON error body — fall back to the status.
    }
    throw new ApiError(response.status, problem);
  }
  return (await response.json()) as AuthConfigView;
}

export async function apiGet<T>(path: string, tenantId: string): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: {
      Accept: 'application/json, application/problem+json',
      ...authHeaders(tenantId),
    },
  });

  if (!response.ok) {
    reportIfUnauthorized(response.status);
    let problem: ProblemDetail | undefined;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Non-JSON error body — fall back to the status.
    }
    throw new ApiError(response.status, problem);
  }

  return (await response.json()) as T;
}

/**
 * GET returning raw text — used for the self-contained HTML report export (GET .../html), which is
 * not JSON and is opened as a Blob, never parsed.
 */
export async function apiGetText(path: string, tenantId: string): Promise<string> {
  const response = await fetch(`${BASE}${path}`, {
    headers: {
      Accept: 'text/html, application/problem+json',
      ...authHeaders(tenantId),
    },
  });

  if (!response.ok) {
    reportIfUnauthorized(response.status);
    let problem: ProblemDetail | undefined;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Non-JSON error body — fall back to the status.
    }
    throw new ApiError(response.status, problem);
  }

  return await response.text();
}

/** POST with optional tenant header in HEADER mode (platform endpoints pass an empty tenantId);
 * OIDC mode attaches the bearer token instead and never sends the tenant header. */
export async function apiPost<T>(path: string, tenantId: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json, application/problem+json',
    'Content-Type': 'application/json',
    ...authHeaders(tenantId, true),
  };
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    reportIfUnauthorized(response.status);
    let problem: ProblemDetail | undefined;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      problem = undefined;
    }
    throw new ApiError(response.status, problem);
  }
  return (await response.json()) as T;
}
