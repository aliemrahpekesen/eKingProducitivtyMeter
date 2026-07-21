import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TenantContext } from '../app/tenantContext';
import {
  useAdminConnectors,
  useAiPolicy,
  useConnectorTypes,
  useCreateTenant,
  useLoadSampleData,
  useRegisterConnector,
  useSetConnectorStatus,
  useStructure,
  useSyncConnector,
  useTenants,
  useTestConnector,
  useUpdateAiPolicy,
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
  useSyncConnector: vi.fn(),
  useLoadSampleData: vi.fn(),
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
// Kept separate from `idleMutation` (shared across the connector/tenant hooks above) so its
// `mutate` calls can be asserted on in isolation, without other buttons' clicks polluting them.
const updateAiPolicySpy = vi.fn();
const updateAiPolicyMutation = {
  mutate: updateAiPolicySpy,
  isPending: false,
  isError: false,
  isSuccess: false,
} as never;

function arm(
  aiPolicy: {
    enabled: boolean;
    provider: 'ollama' | 'openai-compatible' | null;
    baseUrl: string | null;
    model: string | null;
    hasSecret: boolean;
    temperature: number;
    maxTokens: number;
  } = {
    enabled: false,
    provider: null,
    baseUrl: null,
    model: null,
    hasSecret: false,
    temperature: 0.2,
    maxTokens: 512,
  },
): void {
  updateAiPolicySpy.mockClear();
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
  vi.mocked(useSyncConnector).mockReturnValue(idleMutation);
  vi.mocked(useLoadSampleData).mockReturnValue(idleMutation);
  vi.mocked(useAiPolicy).mockReturnValue(okQuery(aiPolicy));
  vi.mocked(useUpdateAiPolicy).mockReturnValue(updateAiPolicyMutation);
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

  describe('AI explanations card (M6-B)', () => {
    it('renders the policy honestly: off by default, deterministic-only status line', () => {
      arm();
      render(
        <TenantContext.Provider value={{ tenantId: 't-1', setTenantId: () => {} }}>
          <AdminPanel />
        </TenantContext.Provider>,
      );

      expect(screen.getByRole('heading', { name: 'AI explanations' })).toBeInTheDocument();
      expect(screen.getByText('Off — deterministic only')).toBeInTheDocument();
      expect(screen.getByLabelText(/Enable AI explanations/)).not.toBeChecked();
      // Defaults come straight from the fetched policy.
      expect(screen.getByLabelText(/Temperature/)).toHaveValue(0.2);
      expect(screen.getByLabelText(/Max tokens/)).toHaveValue(512);
    });

    it('sends the PUT payload built from the form when Save is clicked', () => {
      arm();
      render(
        <TenantContext.Provider value={{ tenantId: 't-1', setTenantId: () => {} }}>
          <AdminPanel />
        </TenantContext.Provider>,
      );

      fireEvent.click(screen.getByLabelText(/Enable AI explanations/));
      fireEvent.change(screen.getByLabelText(/Provider/), { target: { value: 'ollama' } });
      fireEvent.change(screen.getByLabelText(/Endpoint URL/), {
        target: { value: 'http://localhost:11434' },
      });
      fireEvent.change(screen.getByLabelText(/Model/), { target: { value: 'llama3.1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));

      expect(updateAiPolicySpy).toHaveBeenCalledWith(
        {
          enabled: true,
          provider: 'ollama',
          baseUrl: 'http://localhost:11434',
          model: 'llama3.1',
          temperature: 0.2,
          maxTokens: 512,
        },
        expect.anything(),
      );
    });

    it('disables Save with a validation hint when enabling without provider/baseUrl/model', () => {
      arm();
      render(
        <TenantContext.Provider value={{ tenantId: 't-1', setTenantId: () => {} }}>
          <AdminPanel />
        </TenantContext.Provider>,
      );

      fireEvent.click(screen.getByLabelText(/Enable AI explanations/));

      expect(screen.getByText('Pick a provider to enable AI explanations.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    });

    it('never echoes the stored secret into the API key input — shows a hint instead', () => {
      arm({
        enabled: true,
        provider: 'openai-compatible',
        baseUrl: 'https://api.example.com',
        model: 'gpt-4o-mini',
        hasSecret: true,
        temperature: 0.2,
        maxTokens: 512,
      });
      render(
        <TenantContext.Provider value={{ tenantId: 't-1', setTenantId: () => {} }}>
          <AdminPanel />
        </TenantContext.Provider>,
      );

      expect(screen.getByLabelText(/API key/)).toHaveValue('');
      expect(screen.getByText('secret stored')).toBeInTheDocument();
      expect(
        screen.getByText(/On — provider openai-compatible, model gpt-4o-mini/),
      ).toBeInTheDocument();
    });
  });
});
