// Thin wrapper over oidc-client-ts so <AuthProvider> doesn't couple to its constructor shape
// directly (keeps the library swap-able and the provider easy to unit test with vi.mock).
import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

/**
 * Authorization Code + PKCE (oidc-client-ts default for response_type 'code'), redirecting back to
 * the SPA's origin. State is kept in sessionStorage (not localStorage) so a signed-out tab doesn't
 * leak a stale flow into a freshly opened one. Silent renew is off in v0.1 (DEBT: token expiry is
 * handled by the 401 → authExpired path in client.ts, not a background refresh).
 */
export function createUserManager(issuer: string, clientId: string): UserManager {
  return new UserManager({
    authority: issuer,
    client_id: clientId,
    redirect_uri: `${window.location.origin}/`,
    response_type: 'code',
    scope: 'openid profile',
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: false,
  });
}

/** True when the current URL carries an OIDC Authorization Code callback (?code=&state=). */
export function isAuthCallbackUrl(href: string = window.location.href): boolean {
  const params = new URL(href).searchParams;
  return params.has('code') && params.has('state');
}

/** Strips the callback query params, restoring a clean address bar without adding a history entry. */
export function stripAuthCallbackParams(): void {
  const clean = window.location.origin + window.location.pathname;
  window.history.replaceState({}, document.title, clean);
}
