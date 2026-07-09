import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TenantContext } from '../app/tenantContext';
import type { FrictionSummaryView } from '../api/types';
import { useFrictionSummary } from '../api/hooks';
import { FrictionCard } from './FrictionCard';

vi.mock('../api/hooks', () => ({ useFrictionSummary: vi.fn() }));
const mockedUseFriction = vi.mocked(useFrictionSummary);

type FrictionResult = ReturnType<typeof useFrictionSummary>;

function renderCard(): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <FrictionCard />
    </TenantContext.Provider>,
  );
}

const summary: FrictionSummaryView = {
  teamsReporting: 3,
  metric: {
    key: 'engineering_friction',
    name: 'Engineering Friction',
    purpose: 'Where engineering time is lost.',
    formula: 'friction = round(min(100, ...))',
    inputs: { signals: ['wip_limit_breaches'] },
    grain: 'team',
    caveats: 'v1 placeholder; team-level only.',
    gamingRisks: 'Splitting items understates it.',
  },
  teams: [
    {
      teamId: 'a',
      teamName: 'Platform',
      wip: 12,
      wipLimitBreaches: 3,
      oldestInProgressAgeSec: 432_000,
      reviewQueueDepth: 4,
      frictionScore: 74,
    },
    {
      teamId: 'b',
      teamName: 'Payments',
      wip: 6,
      wipLimitBreaches: 1,
      oldestInProgressAgeSec: 172_800,
      reviewQueueDepth: 2,
      frictionScore: 30,
    },
    {
      teamId: 'c',
      teamName: 'Web',
      wip: 3,
      wipLimitBreaches: 0,
      oldestInProgressAgeSec: 86_400,
      reviewQueueDepth: 1,
      frictionScore: 10,
    },
  ],
};

describe('FrictionCard', () => {
  it('renders the worst-first headline score, breakdown and honest caveats', () => {
    mockedUseFriction.mockReturnValue({
      data: summary,
      isLoading: false,
      isError: false,
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.getByLabelText('Top friction score')).toHaveTextContent('74');
    // Platform is the worst team → shown as the hero bottleneck AND first in the breakdown list.
    expect(screen.getAllByText('Platform')).toHaveLength(2);
    expect(screen.getByText('Payments')).toBeInTheDocument();
    expect(screen.getByText('Web')).toBeInTheDocument();
    expect(screen.getByText(/3 teams reporting/)).toBeInTheDocument();
    // FEAT-031: the metric's caveats + gaming risks are surfaced so the score is read honestly.
    expect(screen.getByText('v1 placeholder; team-level only.')).toBeInTheDocument();
    expect(screen.getByText('Splitting items understates it.')).toBeInTheDocument();
  });

  it('shows an empty state when no teams report', () => {
    mockedUseFriction.mockReturnValue({
      data: { ...summary, teams: [], teamsReporting: 0 },
      isLoading: false,
      isError: false,
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.getByText('No team friction data yet')).toBeInTheDocument();
  });

  it('shows a loading state while computing', () => {
    mockedUseFriction.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.getByRole('status')).toHaveTextContent('Computing friction…');
  });
});
