import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TenantContext } from '../app/tenantContext';
import {
  useAdminConnectors,
  useConnectorTypes,
  useCreateTenant,
  useLoadSampleData,
  useRegisterConnector,
  useSetConnectorStatus,
  useStructure,
  useTenants,
  useTestConnector,
} from '../api/hooks';
import { AdminPanel } from './AdminPanel';

vi.mock('../api/hooks', () => ({
  useTenants: vi.fn(),
  useStructure: vi.fn(),
  useConnectorTypes: vi.fn(),
  useAdminConnectors: vi.fn(),
  useCreateTenant: vi.fn(),
  useRegisterConnector: vi.fn(),
  useTestConnector: vi.fn(),
  useSetConnectorStatus: vi.fn(),
  useLoadSampleData: vi.fn(),
}));

const okQuery = (data: unknown) => ({ data, isLoading: false, isError: false }) as never;
const idleMutation = {
  mutate: vi.fn(),
  isPending: false,
  isError: false,
  isSuccess: false,
} as never;

function arm(): void {
  vi.mocked(useTenants).mockReturnValue(okQuery([{ id: 't-1', name: 'Acme', slug: 'acme' }]));
  vi.mocked(useStructure).mockReturnValue(
    okQuery([
      {
        id: 'o-1',
        name: 'Acme Corp',
        slug: 'acme-corp',
        businessUnits: [
          { id: 'b-1', name: 'Engineering', teams: [{ id: 'tm-1', name: 'Platform' }] },
        ],
      },
    ]),
  );
  vi.mocked(useConnectorTypes).mockReturnValue(
    okQuery([
      {
        type: 'jira',
        displayName: 'Atlassian Jira',
        description: 'Work items from Jira.',
        configSchema: '{"properties":{"baseUrl":{"title":"Base URL"}},"required":["baseUrl"]}',
        secretLabel: 'API token',
        syncAvailable: false,
      },
    ]),
  );
  vi.mocked(useAdminConnectors).mockReturnValue(
    okQuery([
      {
        id: 'c-1',
        type: 'jira',
        name: 'Acme Jira',
        status: 'CONFIGURED',
        simulation: false,
        config: { baseUrl: 'https://acme.atlassian.net' },
        hasSecret: true,
      },
    ]),
  );
  vi.mocked(useCreateTenant).mockReturnValue(idleMutation);
  vi.mocked(useRegisterConnector).mockReturnValue(idleMutation);
  vi.mocked(useTestConnector).mockReturnValue(idleMutation);
  vi.mocked(useSetConnectorStatus).mockReturnValue(idleMutation);
  vi.mocked(useLoadSampleData).mockReturnValue(idleMutation);
}

describe('AdminPanel', () => {
  it('renders tenants, integration catalog with honest availability, and structure', () => {
    arm();
    render(
      <TenantContext.Provider value={{ tenantId: 't-1', setTenantId: () => {} }}>
        <AdminPanel />
      </TenantContext.Provider>,
    );

    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('active')).toBeInTheDocument(); // current tenant badge
    // Catalog is honest: Jira is configurable but sync arrives with M2.
    expect(screen.getByText(/config now · sync arrives with M2/)).toBeInTheDocument();
    // Registered connector with masked secret + admin actions.
    expect(screen.getByText('Acme Jira')).toBeInTheDocument();
    expect(screen.getByText(/secret •/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Test' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Load sample data/ })).toBeInTheDocument();
    // Structure tree.
    expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    expect(screen.getByText(/Platform/)).toBeInTheDocument();
  });

  it('opens the schema-driven form when a type is picked and requires the secret', () => {
    arm();
    render(
      <TenantContext.Provider value={{ tenantId: 't-1', setTenantId: () => {} }}>
        <AdminPanel />
      </TenantContext.Provider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Add Atlassian Jira' }));
    expect(screen.getByText('Add Atlassian Jira')).toBeInTheDocument();
    expect(screen.getByText(/Base URL/)).toBeInTheDocument(); // from JSON Schema
    expect(screen.getByText(/API token/)).toBeInTheDocument(); // secret input
    expect(screen.getByText(/envelope-encrypted/)).toBeInTheDocument();
  });

  it('asks for a tenant before showing integrations', () => {
    arm();
    render(
      <TenantContext.Provider value={{ tenantId: '', setTenantId: () => {} }}>
        <AdminPanel />
      </TenantContext.Provider>,
    );
    expect(screen.getByText('Select or create a tenant')).toBeInTheDocument();
  });
});
