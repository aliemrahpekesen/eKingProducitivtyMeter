import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TenantContext } from '../app/tenantContext';
import { useConnectors } from '../api/hooks';
import { ConnectorList } from './ConnectorList';

vi.mock('../api/hooks', () => ({ useConnectors: vi.fn() }));
const mockedUseConnectors = vi.mocked(useConnectors);

type ConnectorsResult = ReturnType<typeof useConnectors>;

function renderList(): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <ConnectorList />
    </TenantContext.Provider>,
  );
}

describe('ConnectorList', () => {
  it('renders connectors with a SIMULATION badge and no invented columns', () => {
    mockedUseConnectors.mockReturnValue({
      data: {
        pages: [
          {
            items: [
              {
                id: '1',
                type: 'jira',
                name: 'Demo Jira (simulation)',
                status: 'REGISTERED',
                simulation: true,
              },
            ],
            hasMore: false,
            nextCursor: null,
          },
        ],
        pageParams: [undefined],
      },
      isLoading: false,
      isError: false,
      hasNextPage: false,
      isFetchingNextPage: false,
    } as unknown as ConnectorsResult);

    renderList();

    expect(screen.getByText('Demo Jira (simulation)')).toBeInTheDocument();
    expect(screen.getByText('jira')).toBeInTheDocument();
    expect(screen.getByText('SIMULATION')).toBeInTheDocument();
    // The contract has no last-sync/checkpoint field, so no such column exists.
    expect(screen.queryByText(/last sync/i)).toBeNull();
  });

  it('shows an empty state when the tenant has no connectors', () => {
    mockedUseConnectors.mockReturnValue({
      data: { pages: [{ items: [], hasMore: false, nextCursor: null }], pageParams: [undefined] },
      isLoading: false,
      isError: false,
      hasNextPage: false,
      isFetchingNextPage: false,
    } as unknown as ConnectorsResult);

    renderList();

    expect(screen.getByText('No connectors registered')).toBeInTheDocument();
  });
});
