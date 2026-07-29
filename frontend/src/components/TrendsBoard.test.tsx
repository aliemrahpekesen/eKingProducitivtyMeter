import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import type { TrendsView } from '../api/types';
import { useMetricTrends } from '../api/hooks';
import { TenantContext } from '../app/tenantContext';
import { TrendsBoard } from './TrendsBoard';

vi.mock('../api/hooks', () => ({
  useMetricTrends: vi.fn(),
}));

// Vanilla echarts is mocked — this suite only smoke-tests the React shell (titles, range buttons,
// hook wiring), not chart internals, which have no DOM surface to assert on anyway.
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}));

const mockedUseMetricTrends = vi.mocked(useMetricTrends);

type TrendsResult = ReturnType<typeof useMetricTrends>;

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

beforeAll(() => {
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});

function renderBoard(): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <TrendsBoard />
    </TenantContext.Provider>,
  );
}

const trends: TrendsView = {
  rangeWeeks: 12,
  teams: [
    {
      teamId: 'platform',
      teamName: 'Platform',
      points: [
        {
          weekStart: '2026-01-05',
          itemsResolved: 10,
          avgCycleSec: 100_000,
          p85CycleSec: 200_000,
          flowEfficiencyPct: 40,
          blockedPct: 20,
          reviewWaitPct: 30,
          frictionScore: 70,
        },
      ],
    },
  ],
};

describe('TrendsBoard', () => {
  it('renders the 4 chart titles and range buttons, defaulting to 12w', () => {
    mockedUseMetricTrends.mockReturnValue({
      data: trends,
      isLoading: false,
      isError: false,
    } as unknown as TrendsResult);

    renderBoard();

    expect(screen.getByText('Friction score')).toBeInTheDocument();
    expect(screen.getByText('Throughput')).toBeInTheDocument();
    expect(screen.getByText('Cycle time')).toBeInTheDocument();
    expect(screen.getByText('Flow efficiency')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '4w' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '12w' })).toHaveClass('active');
    expect(screen.getByRole('button', { name: '26w' })).toBeInTheDocument();
    expect(mockedUseMetricTrends).toHaveBeenCalledWith('tenant-1', 12);
  });

  it('re-queries with the new weeks value when a range button is clicked', () => {
    mockedUseMetricTrends.mockReturnValue({
      data: trends,
      isLoading: false,
      isError: false,
    } as unknown as TrendsResult);

    renderBoard();
    fireEvent.click(screen.getByRole('button', { name: '26w' }));

    expect(mockedUseMetricTrends).toHaveBeenLastCalledWith('tenant-1', 26);
  });

  it('shows an honest empty state when there is no team history', () => {
    mockedUseMetricTrends.mockReturnValue({
      data: { rangeWeeks: 12, teams: [] },
      isLoading: false,
      isError: false,
    } as unknown as TrendsResult);

    renderBoard();

    expect(
      screen.getByText('Not enough history yet — trends fill in as syncs accumulate.'),
    ).toBeInTheDocument();
  });

  it('shows a loading state', () => {
    mockedUseMetricTrends.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as TrendsResult);

    renderBoard();

    expect(screen.getByRole('status')).toHaveTextContent('Loading trends…');
  });
});
