// Smoke tests for the OIDC-vs-HEADER header chrome (M5 Wave S1b): the manual tenant entry
// (TenantBar) is only ever shown in HEADER mode; OIDC mode shows the signed-in user + Sign out
// instead. Deep-links to #admin to keep the hook surface small — everything under Overview/Reports
// is already covered by its own component suite and doesn't need re-mocking here.
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  useAdminConnectors,
  useAiPolicy,
  useConnectorTypes,
  useCreateTenant,
  useLoadSampleData,
  useRegisterConnector,
  useSession,
  useSetConnectorStatus,
  useStructure,
  useSyncConnector,
  useTenants,
  useTestConnector,
  useUpdateAiPolicy,
} from '../api/hooks';
import { AuthContext, type AuthContextValue } from '../auth/authContext';
import { App } from './App';
import { TenantContext } from './tenantContext';

vi.mock('../api/hooks', () => ({
  useTenants: vi.fn(),
  useStructure: vi.fn(),
  useConnectorTypes: vi.fn(),
  useAdminConnectors: vi.fn(),
  useCreateTenant: vi.fn(),
  useRegisterConnector: vi.fn(),
  useTestConnector: vi.fn(),
  useSetConnectorStatus: vi.fn(),
  useSyncConnector: vi.fn(),
  useLoadSampleData: vi.fn(),
  useSession: vi.fn(),
  useAiPolicy: vi.fn(),
  useUpdateAiPolicy: vi.fn(),
}));

const okQuery = (data: unknown) => ({ data, isLoading: false, isError: false }) as never;
const idleMutation = {
  mutate: vi.fn(),
  isPending: false,
  isError: false,
  isSuccess: false,
} as never;

function armAdminHooks(): void {
  vi.mocked(useTenants).mockReturnValue(okQuery([]));
  vi.mocked(useStructure).mockReturnValue(okQuery([]));
  vi.mocked(useConnectorTypes).mockReturnValue(okQuery([]));
  vi.mocked(useAdminConnectors).mockReturnValue(okQuery([]));
  vi.mocked(useCreateTenant).mockReturnValue(idleMutation);
  vi.mocked(useRegisterConnector).mockReturnValue(idleMutation);
  vi.mocked(useTestConnector).mockReturnValue(idleMutation);
  vi.mocked(useSetConnectorStatus).mockReturnValue(idleMutation);
  vi.mocked(useSyncConnector).mockReturnValue(idleMutation);
  vi.mocked(useLoadSampleData).mockReturnValue(idleMutation);
  vi.mocked(useSession).mockReturnValue(okQuery(undefined));
  vi.mocked(useAiPolicy).mockReturnValue(okQuery(undefined));
  vi.mocked(useUpdateAiPolicy).mockReturnValue(idleMutation);
}

function renderApp(auth: AuthContextValue, tenantId = 't-1'): void {
  window.location.hash = '#admin';
  armAdminHooks();
  render(
    <AuthContext.Provider value={auth}>
      <TenantContext.Provider value={{ tenantId, setTenantId: vi.fn() }}>
        <App />
      </TenantContext.Provider>
    </AuthContext.Provider>,
  );
}

describe('App header chrome — HEADER vs OIDC', () => {
  afterEach(() => {
    window.location.hash = '';
  });

  it('HEADER mode: shows the manual tenant entry, no user chip', () => {
    renderApp({ mode: 'HEADER', profileName: null, logout: vi.fn() });

    expect(screen.getByPlaceholderText('paste demo tenant UUID')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
  });

  it('OIDC mode: hides the manual tenant entry, shows the signed-in user + Sign out', () => {
    renderApp({ mode: 'OIDC', profileName: 'Ada Lovelace', logout: vi.fn() });

    expect(screen.queryByPlaceholderText('paste demo tenant UUID')).not.toBeInTheDocument();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });
});
