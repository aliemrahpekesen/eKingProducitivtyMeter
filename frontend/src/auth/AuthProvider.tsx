// M5 Wave S1b — frontend OIDC login flow. Boots by asking the backend how to authenticate
// (GET /api/v1/session/auth, no auth, no tenant header): HEADER renders the app exactly as before
// (zero behavior change — the dev tenant header flow is untouched); OIDC drives an oidc-client-ts
// Authorization Code + PKCE flow against the Keycloak realm the backend names, gating the whole app
// behind a login screen until a valid session exists.
import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { User, UserManager } from 'oidc-client-ts';
import {
  getAuthConfig,
  setAccessTokenGetter,
  setAuthExpiredHandler,
  setAuthMode,
} from '../api/client';
import type { AuthConfigView } from '../api/types';
import { AuthContext, type AuthContextValue } from './authContext';
import { createUserManager, isAuthCallbackUrl, stripAuthCallbackParams } from './oidc';

type OidcStatus = 'pending' | 'ready' | 'unauthenticated';

const HEADER_MODE_VALUE: AuthContextValue = {
  mode: 'HEADER',
  profileName: null,
  logout: () => {},
};

function profileDisplayName(user: User): string {
  return user.profile.name ?? user.profile.preferred_username ?? user.profile.email ?? 'Signed in';
}

// Ends the OIDC session: always clears the local user, then redirects to the IdP's end_session
// endpoint if the discovered metadata advertises one; otherwise falls back to a local reload (the
// access token getter is already cleared, so the next render shows the login screen).
function endOidcSession(userManager: UserManager): void {
  void (async () => {
    await userManager.removeUser();
    try {
      const endSessionEndpoint = await userManager.metadataService.getEndSessionEndpoint();
      if (endSessionEndpoint !== undefined) {
        await userManager.signoutRedirect();
        return;
      }
    } catch {
      // Metadata unavailable — fall through to a local sign-out.
    }
    window.location.reload();
  })();
}

export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  const [config, setConfig] = useState<AuthConfigView | null>(null);
  const [bootError, setBootError] = useState<unknown>(null);
  const [status, setStatus] = useState<OidcStatus>('pending');
  const [profileName, setProfileName] = useState<string | null>(null);
  const userManagerRef = useRef<UserManager | null>(null);

  // 1. Fetch the auth mode once at boot.
  useEffect(() => {
    let cancelled = false;
    getAuthConfig()
      .then((view) => {
        if (!cancelled) {
          setConfig(view);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setBootError(err);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 2. HEADER mode: tell client.ts and render the app unchanged.
  useEffect(() => {
    if (config !== null && config.mode === 'HEADER') {
      setAuthMode('HEADER');
      setStatus('ready');
    }
  }, [config]);

  // 3. OIDC mode: complete a pending callback (if any), else check for an existing session.
  useEffect(() => {
    if (config === null || config.mode !== 'OIDC') {
      return;
    }
    if (config.issuer === null || config.clientId === null) {
      setBootError(new Error('OIDC mode requires an issuer and clientId'));
      return;
    }

    setAuthMode('OIDC');
    const userManager = createUserManager(config.issuer, config.clientId);
    userManagerRef.current = userManager;

    setAuthExpiredHandler(() => {
      setAccessTokenGetter(() => null);
      setProfileName(null);
      setStatus('unauthenticated');
    });

    const applyUser = (user: User | null): void => {
      if (user !== null && user.expired !== true) {
        setAccessTokenGetter(() => user.access_token);
        setProfileName(profileDisplayName(user));
        setStatus('ready');
      } else {
        setAccessTokenGetter(() => null);
        setProfileName(null);
        setStatus('unauthenticated');
      }
    };

    const cameFromCallback = isAuthCallbackUrl();
    let cancelled = false;
    void (async () => {
      try {
        const user = cameFromCallback
          ? await userManager.signinRedirectCallback()
          : await userManager.getUser();
        if (cameFromCallback) {
          stripAuthCallbackParams();
        }
        if (!cancelled) {
          applyUser(user);
        }
      } catch (err) {
        if (cameFromCallback) {
          stripAuthCallbackParams();
        }
        if (!cancelled) {
          setBootError(err);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [config]);

  if (bootError !== null) {
    return (
      <div className="auth-boot state state-error" role="alert">
        <p className="state-title">Could not sign in</p>
        <p className="state-hint">
          {bootError instanceof Error ? bootError.message : 'Unknown error contacting the server.'}
        </p>
      </div>
    );
  }

  if (config === null || (config.mode === 'OIDC' && status === 'pending')) {
    return (
      <div className="auth-boot state state-loading" role="status" aria-live="polite">
        <span className="spinner" aria-hidden="true" />
        <span>Loading…</span>
      </div>
    );
  }

  if (config.mode === 'HEADER') {
    return <AuthContext.Provider value={HEADER_MODE_VALUE}>{children}</AuthContext.Provider>;
  }

  if (status === 'unauthenticated') {
    return (
      <LoginScreen
        onSignIn={() => {
          void userManagerRef.current?.signinRedirect();
        }}
      />
    );
  }

  const value: AuthContextValue = {
    mode: 'OIDC',
    profileName,
    logout: () => {
      const userManager = userManagerRef.current;
      if (userManager !== null) {
        endOidcSession(userManager);
      }
    },
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

function LoginScreen({ onSignIn }: { onSignIn: () => void }): JSX.Element {
  return (
    <div className="auth-login">
      <div className="auth-login-card card">
        <h1>Engineering Intelligence</h1>
        <p className="tagline">Where is engineering time lost?</p>
        <p className="muted">Sign in with your organization account to continue.</p>
        <button type="button" className="btn btn-primary" onClick={onSignIn}>
          Sign in
        </button>
        {import.meta.env.DEV ? (
          <p className="muted auth-dev-hint">Dev demo users: admin.demo / admin_demo_pw, etc.</p>
        ) : null}
      </div>
    </div>
  );
}
