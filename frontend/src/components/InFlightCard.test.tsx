import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { TeamInFlightView } from '../api/types';
import { useInFlight } from '../api/hooks';
import { TenantContext } from '../app/tenantContext';
import { InFlightCard } from './InFlightCard';

vi.mock('../api/hooks', () => ({
  useInFlight: vi.fn(),
}));
const mockedUseInFlight = vi.mocked(useInFlight);

type InFlightResult = ReturnType<typeof useInFlight>;

function renderCard(): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <InFlightCard />
    </TenantContext.Provider>,
  );
}

const teams: TeamInFlightView[] = [
  {
    teamId: 'platform',
    teamName: 'Platform',
    items: [
      {
        workItemKey: 'PLAT-101',
        title: 'Migrate auth service',
        state: 'In Review',
        ageSec: 5 * 86_400,
        blocked: true,
      },
      {
        workItemKey: 'PLAT-102',
        title: 'Fix flaky test',
        state: 'In Progress',
        ageSec: 2 * 86_400,
        blocked: false,
      },
    ],
  },
];

describe('InFlightCard', () => {
  it('renders rows with age and a blocked badge on blocked rows', () => {
    mockedUseInFlight.mockReturnValue({
      data: teams,
      isLoading: false,
      isError: false,
    } as unknown as InFlightResult);

    renderCard();

    expect(screen.getByText('Platform')).toBeInTheDocument();
    expect(screen.getByText('PLAT-101')).toBeInTheDocument();
    expect(screen.getByText('Migrate auth service')).toBeInTheDocument();
    expect(screen.getByText('In Review')).toBeInTheDocument();
    expect(screen.getByText('5d')).toBeInTheDocument();
    expect(screen.getByText('2d')).toBeInTheDocument();
    expect(screen.getByText('⛔ BLOCKED')).toBeInTheDocument();
    // Only the blocked row gets the badge.
    expect(screen.getAllByText('⛔ BLOCKED')).toHaveLength(1);
  });

  it('shows an empty state when nothing is in flight', () => {
    mockedUseInFlight.mockReturnValue({
      data: [{ teamId: 'platform', teamName: 'Platform', items: [] }],
      isLoading: false,
      isError: false,
    } as unknown as InFlightResult);

    renderCard();

    expect(screen.getByText('Nothing in flight.')).toBeInTheDocument();
  });

  it('shows a loading state', () => {
    mockedUseInFlight.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as InFlightResult);

    renderCard();

    expect(screen.getByRole('status')).toHaveTextContent('Loading in-flight work…');
  });

  it('shows an error state with retry', () => {
    mockedUseInFlight.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
    } as unknown as InFlightResult);

    renderCard();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
