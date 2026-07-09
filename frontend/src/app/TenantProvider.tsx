import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { TenantContext } from './tenantContext';

// Dev/demo tenant selection. The tenant id is provided out-of-band (there is no auth yet): it seeds
// from VITE_EIP_TENANT_ID, is editable in the UI, and persists to localStorage. This mirrors the
// backend's dev HeaderTenantResolver and is replaced end-to-end by OIDC in SPRINT-02.
const STORAGE_KEY = 'eip.tenantId';

function initialTenantId(): string {
  const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null;
  return stored ?? import.meta.env.VITE_EIP_TENANT_ID ?? '';
}

export function TenantProvider({ children }: { children: ReactNode }): JSX.Element {
  const [tenantId, setTenantIdState] = useState<string>(initialTenantId);

  useEffect(() => {
    if (typeof localStorage === 'undefined') {
      return;
    }
    if (tenantId) {
      localStorage.setItem(STORAGE_KEY, tenantId);
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
  }, [tenantId]);

  const value = useMemo(
    () => ({ tenantId, setTenantId: (id: string) => setTenantIdState(id.trim()) }),
    [tenantId],
  );

  return <TenantContext.Provider value={value}>{children}</TenantContext.Provider>;
}
