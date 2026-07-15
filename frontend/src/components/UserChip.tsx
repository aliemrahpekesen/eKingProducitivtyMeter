import { useAuth } from '../auth/authContext';

// OIDC-mode header chip: shows the signed-in user + a sign-out control. Rendered instead of
// TenantBar in OIDC mode (there is no manual tenant entry — the tenant comes from the token), see
// App.tsx. Not rendered at all in HEADER mode.
export function UserChip(): JSX.Element {
  const { profileName, logout } = useAuth();

  return (
    <div className="user-chip">
      <span className="user-chip-name" aria-label="Signed in as">
        {profileName ?? 'Signed in'}
      </span>
      <button type="button" className="btn" onClick={logout}>
        Sign out
      </button>
    </div>
  );
}
