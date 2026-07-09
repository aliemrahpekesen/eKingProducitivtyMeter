// Minimal fetch client for the /api/v1 surface. Sends the dev tenant header (X-EIP-Tenant — the
// backend's dev resolver, replaced by OIDC in SPRINT-02) and surfaces RFC 7807 problem+json errors
// (title/detail/status/traceId) so the UI can render honest error states.

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

/** Dev tenant header (backend HeaderTenantResolver). */
export const TENANT_HEADER = 'X-EIP-Tenant';

const BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export async function apiGet<T>(path: string, tenantId: string): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: {
      Accept: 'application/json, application/problem+json',
      [TENANT_HEADER]: tenantId,
    },
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

  return (await response.json()) as T;
}
