import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiGet, setAccessTokenGetter, setAuthExpiredHandler, setAuthMode } from '../api/client';
import { useAuth } from './authContext';
import { AuthProvider } from './AuthProvider';

// oidc-client-ts is mocked wholesale — it would otherwise try to fetch real Keycloak discovery
// metadata over the network. `vi.hoisted` lets the mock instance be referenced both by the
// `vi.mock` factory (hoisted above imports) and by the tests below.
const { userManagerMock } = vi.hoisted(() => {
  const userManagerMock = {
    getUser: vi.fn(),
    signinRedirectCallback: vi.fn(),
    signinRedirect: vi.fn(),
    signoutRedirect: vi.fn(),
    removeUser: vi.fn(),
    metadataService: { getEndSessionEndpoint: vi.fn() },
  };
  return { userManagerMock };
});

vi.mock('oidc-client-ts', () => ({
  UserManager: vi.fn(() => userManagerMock),
  WebStorageStateStore: vi.fn(),
}));

function jsonResponse(body: unknown): Response {
  return { ok: true, status: 200, json: () => Promise.resolve(body) } as unknown as Response;
}

function AuthProbe(): JSX.Element {
  const auth = useAuth();
  return (
    <div>
      <p>app content</p>
      <p data-testid="mode">{auth.mode}</p>
      <p data-testid="profile">{auth.profileName ?? 'none'}</p>
      <button type="button" onClick={auth.logout}>
        Sign out
      </button>
    </div>
  );
}

const headerAuthConfig = { mode: 'HEADER', issuer: null, clientId: null };
const oidcAuthConfig = {
  mode: 'OIDC',
  issuer: 'http://localhost:8180/realms/eip',
  clientId: 'eip-frontend',
};

const validUser = {
  access_token: 'tok-123',
  expired: false,
  profile: { name: 'Ada Lovelace', preferred_username: 'ada', email: 'ada@example.com' },
};

describe('AuthProvider', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState({}, '', '/');
    setAuthMode('HEADER');
    setAccessTokenGetter(() => null);
    setAuthExpiredHandler(() => {});
    userManagerMock.getUser.mockReset();
    userManagerMock.signinRedirectCallback.mockReset();
    userManagerMock.signinRedirect.mockReset().mockResolvedValue(undefined);
    userManagerMock.signoutRedirect.mockReset().mockResolvedValue(undefined);
    userManagerMock.removeUser.mockReset().mockResolvedValue(undefined);
    userManagerMock.metadataService.getEndSessionEndpoint.mockReset();
  });

  afterEach(() => {
    // Restore client.ts module state so other suites see the pre-OIDC (HEADER) default.
    setAuthMode('HEADER');
    setAccessTokenGetter(() => null);
    setAuthExpiredHandler(() => {});
  });

  it('HEADER mode renders the app unchanged with zero login gating', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(headerAuthConfig)));

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByText('app content')).toBeInTheDocument());
    expect(screen.getByTestId('mode')).toHaveTextContent('HEADER');
  });

  it('OIDC mode with no existing user shows the login screen, and Sign in triggers signinRedirect', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(oidcAuthConfig)));
    userManagerMock.getUser.mockResolvedValue(null);

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByText('Sign in')).toBeInTheDocument());
    expect(screen.queryByText('app content')).not.toBeInTheDocument();
    expect(screen.getByText('Engineering Intelligence')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(userManagerMock.signinRedirect).toHaveBeenCalled();
  });

  it('completes the callback, renders the app, and attaches Authorization on subsequent requests', async () => {
    window.history.pushState({}, '', '/?code=abc123&state=xyz');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(oidcAuthConfig)));
    userManagerMock.signinRedirectCallback.mockResolvedValue(validUser);

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByText('app content')).toBeInTheDocument());
    expect(userManagerMock.signinRedirectCallback).toHaveBeenCalled();
    expect(screen.getByTestId('profile')).toHaveTextContent('Ada Lovelace');
    // The callback params are stripped so a refresh doesn't replay the authorization code.
    expect(window.location.search).toBe('');

    // The token getter set by AuthProvider must now be wired into client.ts for every request.
    const apiFetch = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal('fetch', apiFetch);
    await apiGet('/api/v1/whatever', 'irrelevant-in-oidc-mode');

    expect(apiFetch).toHaveBeenCalledWith(
      '/api/v1/whatever',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer tok-123' }),
      }),
    );
    const sentHeaders = apiFetch.mock.calls[0][1].headers as Record<string, string>;
    expect(sentHeaders['X-EIP-Tenant']).toBeUndefined();
  });

  it('signs out via the end_session endpoint when the provider supports it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(oidcAuthConfig)));
    userManagerMock.getUser.mockResolvedValue(validUser);
    userManagerMock.metadataService.getEndSessionEndpoint.mockResolvedValue(
      'http://localhost:8180/realms/eip/protocol/openid-connect/logout',
    );

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByText('app content')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(userManagerMock.removeUser).toHaveBeenCalled());
    await waitFor(() => expect(userManagerMock.signoutRedirect).toHaveBeenCalled());
  });

  it('falls back to a local reload when no end_session endpoint is advertised', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(oidcAuthConfig)));
    userManagerMock.getUser.mockResolvedValue(validUser);
    userManagerMock.metadataService.getEndSessionEndpoint.mockResolvedValue(undefined);

    // jsdom's window.location.reload is non-configurable, so it can't be vi.spyOn'd in place —
    // swap the whole location object for the duration of this test and restore it after.
    const originalLocation = window.location;
    const reloadSpy = vi.fn();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, reload: reloadSpy },
    });

    try {
      render(
        <AuthProvider>
          <AuthProbe />
        </AuthProvider>,
      );

      await waitFor(() => expect(screen.getByText('app content')).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

      await waitFor(() => expect(userManagerMock.removeUser).toHaveBeenCalled());
      await waitFor(() => expect(reloadSpy).toHaveBeenCalled());
      expect(userManagerMock.signoutRedirect).not.toHaveBeenCalled();
    } finally {
      Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
    }
  });

  it('shows an honest error state when the auth config request fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 500, json: () => Promise.resolve({}) }),
    );

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText('app content')).not.toBeInTheDocument();
  });
});
