import { useState } from 'react';
import { useTenant } from '../app/tenantContext';

// Dev tenant selector. There is no auth yet (SPRINT-02 OIDC), so the tenant id is entered here and
// sent as X-EIP-Tenant. Applied on submit (not per keystroke) to avoid refetch churn.
export function TenantBar(): JSX.Element {
  const { tenantId, setTenantId } = useTenant();
  const [draft, setDraft] = useState(tenantId);

  return (
    <form
      className="tenant-bar"
      onSubmit={(event) => {
        event.preventDefault();
        setTenantId(draft);
      }}
    >
      <label htmlFor="tenant" className="tenant-label">
        Tenant
      </label>
      <input
        id="tenant"
        className="tenant-input mono"
        placeholder="paste demo tenant UUID"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        spellCheck={false}
        autoComplete="off"
      />
      <button type="submit" className="btn btn-primary">
        Load
      </button>
    </form>
  );
}
