import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  apiGet,
  apiPost,
  getAuthConfig,
  setAccessTokenGetter,
  setAuthExpiredHandler,
  setAuthMode,
} from './client';

function okJson(body: unknown): Response {
  return { ok: true, status: 200, json: () => Promise.resolve(body) } as unknown as Response;
}

function unauthorized(): Response {
  return {
    ok: false,
    status: 401,
    json: () => Promise.resolve({ title: 'Unauthorized' }),
  } as unknown as Response;
}

describe('api client — auth mode switching (M5 Wave S1b)', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    // Module-level state defaults to HEADER; reset explicitly so test order never leaks state.
    setAuthMode('HEADER');
    setAccessTokenGetter(() => null);
    setAuthExpiredHandler(() => {});
  });

  afterEach(() => {
    setAuthMode('HEADER');
    setAccessTokenGetter(() => null);
    setAuthExpiredHandler(() => {});
  });

  it('HEADER mode (default): apiGet sends X-EIP-Tenant and no Authorization — unchanged from pre-OIDC behavior', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(okJson({ tenantId: 't-1', organizationName: null }));
    vi.stubGlobal('fetch', fetchMock);

    await apiGet('/api/v1/session', 't-1');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers['X-EIP-Tenant']).toBe('t-1');
    expect(headers.Authorization).toBeUndefined();
  });

  it('HEADER mode: apiPost omits the tenant header entirely for an empty tenantId (platform endpoints)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 't-1' }));
    vi.stubGlobal('fetch', fetchMock);

    await apiPost('/api/v1/admin/tenants', '', { name: 'Acme', slug: 'acme' });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers['X-EIP-Tenant']).toBeUndefined();
  });

  it('OIDC mode: apiGet attaches Authorization: Bearer <token> and never sends X-EIP-Tenant', async () => {
    setAuthMode('OIDC');
    setAccessTokenGetter(() => 'my-access-token');
    const fetchMock = vi
      .fn()
      .mockResolvedValue(okJson({ tenantId: 't-1', organizationName: null }));
    vi.stubGlobal('fetch', fetchMock);

    await apiGet('/api/v1/session', 'whatever-tenant-value');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer my-access-token');
    expect(headers['X-EIP-Tenant']).toBeUndefined();
  });

  it('OIDC mode: apiPost also attaches Authorization and never sends X-EIP-Tenant', async () => {
    setAuthMode('OIDC');
    setAccessTokenGetter(() => 'my-access-token');
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'r-1' }));
    vi.stubGlobal('fetch', fetchMock);

    await apiPost('/api/v1/reports', 'whatever-tenant-value', { type: 'EXEC_SUMMARY', weeks: 4 });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer my-access-token');
    expect(headers['X-EIP-Tenant']).toBeUndefined();
  });

  it('OIDC mode: a 401 response invokes the registered authExpired handler', async () => {
    setAuthMode('OIDC');
    setAccessTokenGetter(() => 'expired-token');
    const onExpired = vi.fn();
    setAuthExpiredHandler(onExpired);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(unauthorized()));

    await expect(apiGet('/api/v1/session', 't-1')).rejects.toThrow();
    expect(onExpired).toHaveBeenCalled();
  });

  it('HEADER mode: a 401 response never invokes the authExpired handler', async () => {
    const onExpired = vi.fn();
    setAuthExpiredHandler(onExpired);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(unauthorized()));

    await expect(apiGet('/api/v1/session', 't-1')).rejects.toThrow();
    expect(onExpired).not.toHaveBeenCalled();
  });

  it('getAuthConfig fetches /api/v1/session/auth with no tenant header and no Authorization', async () => {
    // Deliberately arm OIDC-mode auth state first — getAuthConfig must ignore it (it's the one
    // endpoint that is never authenticated).
    setAuthMode('OIDC');
    setAccessTokenGetter(() => 'some-token');
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        okJson({ mode: 'OIDC', issuer: 'http://issuer', clientId: 'eip-frontend' }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const config = await getAuthConfig();

    expect(config).toEqual({ mode: 'OIDC', issuer: 'http://issuer', clientId: 'eip-frontend' });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/session/auth');
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
    expect(headers['X-EIP-Tenant']).toBeUndefined();
  });
});
