import { createContext, useContext } from 'react';

export type AuthMode = 'HEADER' | 'OIDC';

export interface AuthContextValue {
  /** HEADER: today's dev tenant header, no auth. OIDC: Keycloak Authorization Code + PKCE. */
  mode: AuthMode;
  /** Signed-in user's display name (OIDC only) — null in HEADER mode. */
  profileName: string | null;
  /** Ends the OIDC session (removeUser, then signoutRedirect if supported, else local reload). No-op in HEADER mode. */
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (ctx === null) {
    throw new Error('useAuth must be used within <AuthProvider>');
  }
  return ctx;
}
