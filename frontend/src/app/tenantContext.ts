import { createContext, useContext } from 'react';

export interface TenantContextValue {
  /** The tenant id sent as X-EIP-Tenant. Empty when none is selected. */
  tenantId: string;
  setTenantId: (id: string) => void;
}

export const TenantContext = createContext<TenantContextValue | null>(null);

export function useTenant(): TenantContextValue {
  const ctx = useContext(TenantContext);
  if (ctx === null) {
    throw new Error('useTenant must be used within <TenantProvider>');
  }
  return ctx;
}
